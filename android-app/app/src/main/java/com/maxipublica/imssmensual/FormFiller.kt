package com.maxipublica.imssmensual

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Orquesta el llenado paso a paso (cap. 4.1). Un script JS = un mensaje de respuesta. */
class FormFiller(private val webView: () -> WebView?, private val log: (String) -> Unit, private val hideKeyboard: () -> Unit) {
    private var pending: CompletableDeferred<JSONObject>? = null
    private var ready: CompletableDeferred<Boolean>? = null
    var captchaSolver: (suspend (JSONObject) -> Boolean)? = null   // resuelve campos kind=captcha_image; null = lo hace el humano
    var lastReady: String = ""   // último READY_RESULT (sin datos personales): para explicar por qué falló una guarda

    fun onMessage(json: JSONObject) {
        if (json.optString("type") == "READY_RESULT") lastReady = "textOk=${json.opt("textOk")} missing=${json.opt("missing")} bodyLen=${json.opt("bodyLen")}"
        when (json.optString("type")) {
            "FILL_RESULT", "FILL_ERROR" -> pending?.let { if (!it.isCompleted) it.complete(json) }
            "READY_RESULT" -> ready?.let { if (!it.isCompleted) it.complete(json.optBoolean("ok")) }
        }
    }

    suspend fun runStep(label: String, script: String, timeoutMs: Long = 15_000): JSONObject? {
        val d = CompletableDeferred<JSONObject>(); pending = d
        webView()?.post { webView()?.evaluateJavascript(script, null) }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        if (r == null) log("[$label] TIMEOUT (sin respuesta)")
        else log("[$label] ${if (r.optBoolean("ok")) "OK" else "FALLO"}: ${r.optString("message")}")
        hideKeyboard(); delay(600)
        return r
    }

    /** «Pantalla lista»: 2 lecturas OK consecutivas, cada 500 ms, hasta 20 s (cap. 2.5). */
    suspend fun waitReady(script: String = ProbeScripts.READY): Boolean {
        var okCount = 0
        repeat(40) {
            val d = CompletableDeferred<Boolean>(); ready = d
            webView()?.post { webView()?.evaluateJavascript(script, null) }
            val ok = withTimeoutOrNull(1500) { d.await() } ?: false
            okCount = if (ok) okCount + 1 else 0
            if (okCount >= 2) { delay(500); return true }
            delay(500)
        }
        return false
    }

    /** Llena una pantalla según schema+mapping y una fila CSV. Devuelve true solo si todo lo requerido quedó verificado. */
    suspend fun fill(schema: JSONObject, mapping: JSONObject, row: Map<String, String>): Boolean {
        val screen = schema.optString("screen_id")
        val url = webView()?.url.orEmpty()
        if (!Regex(schema.optString("url_pattern")).containsMatchIn(url)) { log("[$screen] FALLO pantalla_desconocida (host/url) url=$url"); return false }
        val rd = schema.getJSONObject("ready")
        val needs = rd.getJSONArray("needs").let { a -> (0 until a.length()).map { a.getString(it) } }
        if (!waitReady(ProbeScripts.ready(rd.getString("text_regex"), needs))) {
            log("[$screen] FALLO pantalla_desconocida: no están los marcadores de esta pantalla (texto='${rd.getString("text_regex")}', controles=$needs) url=$url"); return false
        }
        val fields = schema.getJSONArray("fields")
        val order = (0 until fields.length()).map { fields.getJSONObject(it) }.sortedBy { it.optInt("order") }
        val map = mapping.getJSONObject("map")
        for (f in order) {
            val key = f.getString("key")
            if (f.optString("kind") == "captcha_human") { log("[$key] omitido: lo resuelve el humano"); continue }
            if (f.optString("kind") == "captcha_image") {
                val solver = captchaSolver
                if (solver == null) { log("[$key] omitido: lo resuelve el humano"); continue }
                if (!solver(f) && f.optBoolean("required")) { log("[$screen] STOP campo_requerido_fallido: $key"); return false }
                continue
            }
            val kind = f.optString("kind")
            val labels = f.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }
            val dom = f.optJSONObject("dom")?.optString("id").orEmpty()
            if (kind == "readonly") {   // lo rellena el sitio (p. ej. tras «Buscar»): solo se verifica
                val r = runStep(key, FillScripts.readonlyFilled(key, labels, dom))
                if (r?.optBoolean("ok") != true && f.optBoolean("required")) { log("[$screen] STOP campo_requerido_fallido: $key"); return false }
                continue
            }
            val m = map.optJSONObject(key) ?: run { log("[$key] sin mapeo, omitido"); null } ?: continue
            val value: String = if (m.has("const")) m.get("const").toString()
                else CsvSource.transform(row[m.getString("csv")].orEmpty(), m.optString("transform").ifEmpty { null })
            if (value.isEmpty()) { log("[$key] valor vacío en CSV: no se toca"); continue }   // vacío = no tocar (L23)
            if (m.has("const") && kind == "checkbox") log("[$key] marcado por la automatización (decisión del usuario)")
            val r = when (kind) {
                "text" -> runStep(key, FillScripts.text(key, labels, dom, value))
                "text_lookup" -> {
                    val t = runStep(key, FillScripts.text(key, labels, dom, value))
                    val lk = f.getJSONObject("lookup")
                    val lkLabels = lk.getJSONArray("button_labels").let { a -> (0 until a.length()).map { a.getString(it) } }
                    if (t?.optBoolean("ok") == true) runStep("$key:buscar", FillScripts.clickButton("$key:buscar", lkLabels, lk.optString("dom"))) else t
                }
                "select_native" -> runStep(key, FillScripts.selectNative(key, labels, dom, value), 30_000)
                "checkbox" -> runStep(key, FillScripts.setCheckbox(key, labels, dom, value.toBoolean()))
                else -> { log("[$key] tipo '$kind' no soportado"); null }
            }
            if (r?.optBoolean("ok") != true && f.optBoolean("required")) {
                log("[$screen] STOP campo_requerido_fallido: $key"); return false
            }
        }
        // Solo informa del botón de avance; NUNCA lo pulsa
        schema.optJSONArray("actions")?.let { acts ->
            for (i in 0 until acts.length()) {
                val ac = acts.getJSONObject(i)
                if (ac.optString("key") == "continuar") runStep("continuar", FillScripts.buttonState("continuar", ac.optJSONObject("dom")?.optString("id").orEmpty()))
            }
        }
        log("[$screen] LLENADO COMPLETO. Falta revisar el captcha y Continuar (humano).")
        return true
    }
}
