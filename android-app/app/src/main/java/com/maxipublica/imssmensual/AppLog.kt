package com.maxipublica.imssmensual

import android.util.Log
import androidx.compose.runtime.mutableStateListOf

/** Log a logcat (TAG ImssForm) espejado en pantalla. Enmascara CURP y correos (P10). */
object AppLog {
    const val TAG = "ImssForm"
    private const val MAX_LINES = 300
    private val CURP = Regex("[A-Z]{4}\\d{6}[HM][A-Z]{5}[A-Z0-9]\\d", RegexOption.IGNORE_CASE)
    private val KEY32 = Regex("\\b[0-9a-fA-F]{32}\\b")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")

    val lines = mutableStateListOf<String>()

    fun mask(s: String): String =
        s.replace(CURP) { it.value.take(4) + "**************" }.replace(EMAIL, "***@***").replace(KEY32, "***CLAVE***")

    fun log(msg: String) {
        val m = mask(msg)
        // logcat corta ≈4 KB por línea: trocear
        m.chunked(3500).forEach { Log.d(TAG, it) }
        lines.add(m)
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }
}
