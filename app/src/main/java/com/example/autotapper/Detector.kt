package com.example.autotapper

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 템플릿 매칭으로 화면 속 타겟 이미지를 (여러 개) 찾는다.
 * 프레임/템플릿 모두 축소된 좌표계에서 동작한다.
 */
class Detector(
    templateRgba: Mat,
    private val useColor: Boolean,
    private val threshold: Double,
    private val maxTargets: Int = 20,
) {
    private val base = Mat()
    private val tpl = Mat()
    private val result = Mat()
    private val aspect: Double

    /** 축소 좌표계에서의 템플릿 가로 크기 (0 = 아직 크기 못 찾음) */
    var tplWidth = 0
        private set
    var tplHeight = 0
        private set
    var lastScore = 0.0
        private set

    init {
        Imgproc.cvtColor(templateRgba, base, convCode())
        aspect = base.rows().toDouble() / base.cols()
    }

    fun convCode() = if (useColor) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_RGBA2GRAY

    private fun setWidth(w: Int) {
        tplWidth = w
        tplHeight = max(1, (w * aspect).roundToInt())
        Imgproc.resize(base, tpl, Size(tplWidth.toDouble(), tplHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
    }

    private fun scoreAt(frame: Mat, w: Int): Double {
        val h = (w * aspect).roundToInt()
        if (w < 6 || h < 6 || w > frame.cols() || h > frame.rows()) return -1.0
        setWidth(w)
        Imgproc.matchTemplate(frame, tpl, result, Imgproc.TM_CCOEFF_NORMED)
        return Core.minMaxLoc(result).maxVal
    }

    /**
     * 화면에 보이는 아이콘 크기를 자동으로 찾는다 (여러 배율 시도).
     * 찾으면 true. 타겟이 화면에 보일 때까지 매 프레임 호출하면 된다.
     */
    fun calibrate(frame: Mat): Boolean {
        val maxW = min(frame.cols(), (frame.rows() / aspect).toInt())
        val minW = 12 // 너무 작은 템플릿은 엉뚱한 곳과도 잘 맞아서 제외
        if (maxW <= minW) return false
        val steps = 40
        val ratio = (maxW.toDouble() / minW).pow(1.0 / (steps - 1))
        var bestW = 0
        var best = -1.0
        var prevW = -1
        for (i in 0 until steps) {
            val w = (minW * ratio.pow(i)).roundToInt()
            if (w == prevW) continue
            prevW = w
            val s = scoreAt(frame, w)
            if (s > best) { best = s; bestW = w }
        }
        // 주변을 1px 단위로 미세 조정
        val lo = max(minW, (bestW / ratio).toInt())
        val hi = min(maxW, (bestW * ratio).toInt() + 1)
        for (w in lo..hi) {
            val s = scoreAt(frame, w)
            if (s > best) { best = s; bestW = w }
        }
        lastScore = best
        return if (best >= threshold) {
            setWidth(bestW); true
        } else {
            tplWidth = 0; false
        }
    }

    /** 찾은 타겟들의 중심 좌표 (축소 좌표계). 점수 높은 순. */
    fun detect(frame: Mat, out: MutableList<Point>) {
        out.clear()
        if (tplWidth == 0 || tplWidth > frame.cols() || tplHeight > frame.rows()) return
        Imgproc.matchTemplate(frame, tpl, result, Imgproc.TM_CCOEFF_NORMED)
        val hw = tplWidth / 2.0
        val hh = tplHeight / 2.0
        while (out.size < maxTargets) {
            val mm = Core.minMaxLoc(result)
            if (mm.maxVal < threshold) break
            val p = mm.maxLoc
            out.add(Point(p.x + hw, p.y + hh))
            // 같은 대상을 중복으로 잡지 않도록 주변 지우기
            Imgproc.rectangle(result, Point(p.x - hw, p.y - hh), Point(p.x + hw, p.y + hh), Scalar(-1.0), -1)
        }
    }
}
