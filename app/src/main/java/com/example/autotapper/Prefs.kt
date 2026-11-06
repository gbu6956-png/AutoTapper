package com.example.autotapper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** 설정값 저장 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 일치도 기준 (50~99 %) — 높을수록 엄격 */
    var thresholdPct: Int
        get() = sp.getInt("threshold2", 70)
        set(v) = sp.edit().putInt("threshold2", v.coerceIn(40, 99)).apply()

    /** 같은 자리를 다시 누르기 전 대기시간(ms) */
    var cooldownMs: Int
        get() = sp.getInt("cooldown", 120)
        set(v) = sp.edit().putInt("cooldown", v.coerceIn(0, 5000)).apply()

    /** 캡처 축소 배율 (클수록 빠르지만 작은 아이콘은 놓칠 수 있음) */
    var downscale: Int
        get() = sp.getInt("downscale", 3)
        set(v) = sp.edit().putInt("downscale", v.coerceIn(1, 8)).apply()

    /** 탭 누름 시간(ms) */
    var tapMs: Int
        get() = sp.getInt("tapMs2", 40)
        set(v) = sp.edit().putInt("tapMs2", v.coerceIn(1, 500)).apply()

    /** 색상까지 비교 (느리지만 색만 다른 가짜 아이콘을 구분) */
    var useColor: Boolean
        get() = sp.getBoolean("useColor", false)
        set(v) = sp.edit().putBoolean("useColor", v).apply()

    /** 여러 개를 동시에(멀티터치) 탭 / 끄면 아주 짧은 간격으로 하나씩 */
    var simultaneous: Boolean
        get() = sp.getBoolean("simultaneous2", false)
        set(v) = sp.edit().putBoolean("simultaneous2", v).apply()

    companion object {
        const val CUSTOM_FILE = "custom_target.png"

        /** 사용자가 고른 이미지가 있으면 그것, 없으면 기본(첨부) 이미지 */
        fun loadTarget(context: Context): Bitmap {
            val f = File(context.filesDir, CUSTOM_FILE)
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
            context.assets.open("target.png").use { return BitmapFactory.decodeStream(it) }
        }
    }
}
