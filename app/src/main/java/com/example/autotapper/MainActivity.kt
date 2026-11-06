package com.example.autotapper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    companion object {
        /** 이 화면이 보이는 동안은 탭하지 않음 */
        @Volatile var visible = false
            private set
    }

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var a11yText: TextView
    private lateinit var preview: ImageView
    private lateinit var thresholdEdit: EditText
    private lateinit var cooldownEdit: EditText
    private lateinit var downscaleEdit: EditText
    private lateinit var tapMsEdit: EditText
    private lateinit var colorSwitch: Switch
    private lateinit var simulSwitch: Switch

    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            statusText.text = "상태: ${CaptureService.status}"
            val on = TapService.instance != null
            a11yText.text = if (on) "✅ 접근성 권한 켜짐" else "❌ 접근성 권한 꺼짐 — 아래 1번 버튼으로 켜주세요"
            a11yText.setTextColor(if (on) Color.rgb(20, 130, 40) else Color.rgb(200, 30, 30))
            ui.postDelayed(this, 300)
        }
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                .putExtra(CaptureService.EXTRA_DATA, res.data)
            startForegroundService(i)
            Toast.makeText(this, "시작! 게임 화면으로 이동하세요", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "화면 캡처 권한이 필요합니다 (전체 화면 선택)", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val bmp = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("decode")
            val maxSide = maxOf(bmp.width, bmp.height)
            val scaled = if (maxSide > 800) {
                val s = 800f / maxSide
                Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            } else bmp
            FileOutputStream(File(filesDir, Prefs.CUSTOM_FILE)).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            refreshPreview()
            Toast.makeText(this, "타겟 이미지를 바꿨습니다", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "이미지를 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        ui.post(poll)
    }

    override fun onPause() {
        visible = false
        ui.removeCallbacks(poll)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }.also { col.addView(it) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; setAllCaps(false); setOnClickListener { onClick() }
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        fun numberRow(label: String, value: Int): EditText {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = label; textSize = 15f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString()); gravity = Gravity.END
            }
            row.addView(e, LinearLayout.LayoutParams(dp(96), WRAP_CONTENT))
            col.addView(row)
            return e
        }
        fun switchRow(label: String, value: Boolean) = Switch(this).apply {
            text = label; textSize = 15f; isChecked = value
            setPadding(0, dp(8), 0, dp(8))
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        text("자동 탭", 24f, true)
        a11yText = text("")
        statusText = text("상태: -")

        text("타겟 이미지", 17f, true)
        preview = ImageView(this).apply { adjustViewBounds = true }
        col.addView(preview, LinearLayout.LayoutParams(dp(120), dp(120)))
        button("타겟 이미지 바꾸기 (갤러리)") { pickImage.launch("image/*") }
        button("기본 이미지로 되돌리기") { File(filesDir, Prefs.CUSTOM_FILE).delete(); refreshPreview() }

        text("설정", 17f, true)
        thresholdEdit = numberRow("일치도 기준 % (못 찾으면 낮추기, 기본 70)", prefs.thresholdPct)
        cooldownEdit = numberRow("같은 자리 재탭 대기 ms (기본 120)", prefs.cooldownMs)
        downscaleEdit = numberRow("캡처 축소 배율 (클수록 빠름, 기본 3)", prefs.downscale)
        tapMsEdit = numberRow("탭 누름 시간 ms (기본 40)", prefs.tapMs)
        colorSwitch = switchRow("색상까지 비교 (느리지만 정확)", prefs.useColor)
        simulSwitch = switchRow("여러 개 동시에 탭 (멀티터치)", prefs.simultaneous)

        text("실행", 17f, true)
        button("1. 접근성 권한 켜기") {
            Toast.makeText(this, "'설치된 앱' → '자동 탭' → 사용 켜기", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. 시작") { onStartClicked() }
        button("3. 탭 테스트 화면 열기 (시작 후)") {
            startActivity(Intent(this, TestActivity::class.java))
        }
        button("중지") { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) }

        text(
            "※ 시작하면 화면 공유 창에서 '전체 화면'을 선택하세요.\n" +
            "※ 실행 중 상태는 알림창을 내리면 보입니다.\n" +
            "※ 테스트 화면에서 숫자가 올라가면 앱은 정상입니다.\n" +
            "※ 시작 후 게임 화면에 타겟이 처음 보일 때 크기를 자동으로 맞춥니다.\n" +
            "※ 화면을 회전했다면 중지 후 다시 시작하세요.",
            13f
        )

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col)
        }
    }

    private fun refreshPreview() {
        preview.setImageBitmap(Prefs.loadTarget(this))
    }

    private fun saveSettings() {
        thresholdEdit.text.toString().toIntOrNull()?.let { prefs.thresholdPct = it }
        cooldownEdit.text.toString().toIntOrNull()?.let { prefs.cooldownMs = it }
        downscaleEdit.text.toString().toIntOrNull()?.let { prefs.downscale = it }
        tapMsEdit.text.toString().toIntOrNull()?.let { prefs.tapMs = it }
        prefs.useColor = colorSwitch.isChecked
        prefs.simultaneous = simulSwitch.isChecked
        thresholdEdit.setText(prefs.thresholdPct.toString())
        cooldownEdit.setText(prefs.cooldownMs.toString())
        downscaleEdit.setText(prefs.downscale.toString())
        tapMsEdit.setText(prefs.tapMs.toString())
    }

    private fun onStartClicked() {
        saveSettings()
        if (TapService.instance == null) {
            Toast.makeText(this, "먼저 접근성 권한을 켜주세요 (1번 버튼)", Toast.LENGTH_LONG).show()
            return
        }
        if (CaptureService.running) {
            Toast.makeText(this, "이미 실행 중입니다. 설정을 바꿨다면 중지 후 다시 시작하세요", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
