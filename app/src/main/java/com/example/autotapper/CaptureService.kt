package com.example.autotapper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * 화면을 계속 캡처(축소 해상도)하면서 타겟을 찾고, 찾는 즉시 TapService로 탭한다.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "autotap"
        private const val NOTIF_ID = 1

        @Volatile var running = false
            private set
        @Volatile var status = "중지됨"
            private set
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var detector: Detector? = null
    private var prefs: Prefs? = null

    // 화면/캡처 크기
    private var screenW = 0
    private var screenH = 0
    private var capW = 0
    private var capH = 0
    private var scaleX = 1f
    private var scaleY = 1f

    // 재사용 버퍼
    private var bytes: ByteArray? = null
    private var rgbaFull: Mat? = null
    private val frame = Mat()
    private val found = ArrayList<Point>()
    private val tapX = FloatArray(32)
    private val tapY = FloatArray(32)

    // 같은 자리 연타 방지
    private val recentX = FloatArray(64)
    private val recentY = FloatArray(64)
    private val recentT = LongArray(64)
    private var recentN = 0

    // 통계
    private var frames = 0
    private var procSum = 0L
    private var taps = 0L
    private var statWindowStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (running) return
        // Android 14+: getMediaProjection 전에 반드시 foreground 시작
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null || !OpenCVLoader.initLocal()) {
            status = "시작 실패 (캡처 권한 또는 OpenCV 로드 실패)"
            stopSelf(); return
        }

        val p = Prefs(this).also { prefs = it }
        val tplMat = Mat()
        Utils.bitmapToMat(Prefs.loadTarget(this), tplMat)
        detector = Detector(tplMat, p.useColor, p.thresholdPct / 100.0)
        tplMat.release()

        // 실제 화면 크기
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
        val ds = p.downscale
        capW = (screenW / ds) and 1.inv()
        capH = (screenH / ds) and 1.inv()
        scaleX = screenW.toFloat() / capW
        scaleY = screenH.toFloat() / capH

        val t = HandlerThread("capture", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper).also { handler = it }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) { status = "캡처 권한 없음"; stopSelf(); return }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ processFrame(it) }, h)
        display = proj.createVirtualDisplay(
            "autotap", capW, capH, maxOf(1, resources.displayMetrics.densityDpi / ds),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
        )
        running = true
        statWindowStart = SystemClock.uptimeMillis()
        status = "실행 중 – 타겟 크기 찾는 중… (게임 화면으로 이동하세요)"
    }

    private fun processFrame(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val t0 = SystemClock.uptimeMillis()
        try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buf = plane.buffer
            val need = rowStride * capH
            var b = bytes
            if (b == null || b.size != need) { b = ByteArray(need); bytes = b }
            buf.rewind()
            val len = minOf(buf.remaining(), need)
            buf.get(b, 0, len)

            val rowPixels = rowStride / pixelStride
            var full = rgbaFull
            if (full == null || full.cols() != rowPixels) {
                full?.release()
                full = Mat(capH, rowPixels, CvType.CV_8UC4); rgbaFull = full
            }
            full.put(0, 0, b) // 마지막 행의 남는 패딩은 잘라낸 영역이라 무관
            val view = full.submat(0, capH, 0, capW)
            val det = detector ?: return
            Imgproc.cvtColor(view, frame, det.convCode())
            view.release()
        } finally {
            image.close()
        }

        // 앱 자기 화면이 보일 때는 동작하지 않음 (미리보기 이미지를 누르지 않도록)
        if (MainActivity.visible) {
            status = "실행 중 – 이 앱을 벗어나면 동작합니다"
            return
        }
        val det = detector ?: return
        if (det.tplWidth == 0) {
            if (det.calibrate(frame)) {
                status = "실행 중 – 크기 고정 (${(det.tplWidth * scaleX).toInt()}px)"
            } else {
                status = "실행 중 – 타겟 크기 찾는 중… (최고 일치도 ${(det.lastScore * 100).toInt()}%)"
                return
            }
        }

        det.detect(frame, found)
        val now = SystemClock.uptimeMillis()
        val p = prefs ?: return
        if (found.isNotEmpty()) {
            val radius = det.tplWidth * scaleX * 0.5f
            val r2 = radius * radius
            val cd = p.cooldownMs
            // 오래된 기록 정리
            var k = 0
            for (i in 0 until recentN) if (now - recentT[i] < cd) {
                recentX[k] = recentX[i]; recentY[k] = recentY[i]; recentT[k] = recentT[i]; k++
            }
            recentN = k
            var n = 0
            for (pt in found) {
                val x = (pt.x * scaleX).toFloat()
                val y = (pt.y * scaleY).toFloat()
                var dup = false
                for (i in 0 until recentN) {
                    val dx = recentX[i] - x; val dy = recentY[i] - y
                    if (dx * dx + dy * dy < r2) { dup = true; break }
                }
                if (dup || n >= tapX.size) continue
                tapX[n] = x; tapY[n] = y; n++
            }
            if (n > 0 && TapService.instance?.tap(tapX, tapY, n, p.tapMs.toLong(), p.simultaneous) == true) {
                taps += n
                for (i in 0 until n) if (recentN < recentX.size) {
                    recentX[recentN] = tapX[i]; recentY[recentN] = tapY[i]; recentT[recentN] = now; recentN++
                }
            }
        }

        frames++
        procSum += now - t0
        if (now - statWindowStart >= 1000) {
            val fps = frames * 1000f / (now - statWindowStart)
            val avg = if (frames > 0) procSum.toFloat() / frames else 0f
            val a11y = if (TapService.instance == null) " ⚠ 접근성 꺼짐" else ""
            status = "실행 중 – ${"%.0f".format(fps)}fps, 인식 ${"%.1f".format(avg)}ms, 누른 횟수 $taps$a11y"
            frames = 0; procSum = 0; statWindowStart = now
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "자동 탭 실행 중", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("자동 탭 실행 중")
            .setContentText("타겟 이미지가 보이면 바로 탭합니다")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "중지", stopPi).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        status = "중지됨"
        val h = handler
        val cleanup = Runnable {
            try { display?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            display = null; reader = null; projection = null
            rgbaFull?.release(); rgbaFull = null
            thread?.quitSafely()
        }
        if (h != null) h.post(cleanup) else cleanup.run()
        super.onDestroy()
    }
}
