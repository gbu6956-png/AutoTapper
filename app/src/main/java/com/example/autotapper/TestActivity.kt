package com.example.autotapper

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.random.Random

/**
 * 탭 테스트 화면: 타겟 이미지 3개가 무작위 위치에 나오고, 눌리면 다른 곳으로 이동한다.
 * 자동 탭이 실행 중이면 숫자가 계속 올라가야 정상.
 */
class TestActivity : Activity() {
    private var hits = 0
    private lateinit var root: FrameLayout
    private lateinit var counter: TextView
    private val moles = ArrayList<ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(120, 170, 80)) }
        counter = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            text = "자동 탭 테스트\n맞춘 횟수: 0"
            setPadding(0, (48 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(counter, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val bmp = Prefs.loadTarget(this)
        val size = (90 * resources.displayMetrics.density).toInt()
        repeat(3) {
            val iv = ImageView(this).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { v ->
                    hits++
                    counter.text = "자동 탭 테스트\n맞춘 횟수: $hits"
                    place(v as ImageView)
                }
            }
            root.addView(iv, FrameLayout.LayoutParams(size, size))
            moles.add(iv)
        }
        setContentView(root)
        root.post { moles.forEach { place(it) } }
    }

    private fun place(v: ImageView) {
        val w = root.width - v.layoutParams.width
        val h = root.height - v.layoutParams.height
        val top = (160 * resources.displayMetrics.density).toInt()
        if (w <= 0 || h <= top) return
        v.x = Random.nextInt(w).toFloat()
        v.y = (top + Random.nextInt(h - top)).toFloat()
    }
}
