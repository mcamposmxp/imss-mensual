package com.maxipublica.imssmensual

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Cliente mínimo de Anti-Captcha (ImageToTextTask). La clave vive en files/.env (copiado por adb, fuera de git) y NUNCA se registra. */
class AntiCaptcha(private val filesDir: File, private val log: (String) -> Unit) {
    data class Solved(val text: String, val taskId: Long, val cost: Double)

    private companion object {
        const val HOST = "api.anti-captcha.com"
        const val DEFAULT_CAP_USD = 0.50
    }

    private fun env(): Map<String, String> {
        val f = File(filesDir, ".env")
        if (!f.exists()) return emptyMap()
        return f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { l -> l.substringBefore("=").trim() to l.substringAfter("=").trim().trim('"', '\'') }
    }

    private fun key() = env()["ANTICAPTCHA_KEY"].orEmpty()
    fun available() = key().isNotEmpty()
    private fun capUsd() = env()["ANTICAPTCHA_DAILY_CAP_USD"]?.toDoubleOrNull() ?: DEFAULT_CAP_USD

    /** Solo https al host del proveedor, sin redirecciones, con tiempos de conexión/lectura acotados (cap. 7.9). */
    private suspend fun call(method: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("https://$HOST/$method")
        require(url.host == HOST)
        val c = url.openConnection() as HttpsURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.instanceFollowRedirects = false; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    }

    private var lastBalanceAt = 0L

    /** La doc pide no llamar getBalance más de una vez cada 30 s. */
    suspend fun balance(): Double? {
        if (!available()) { log("[anticaptcha] sin ANTICAPTCHA_KEY en files/.env"); return null }
        if (System.currentTimeMillis() - lastBalanceAt < 30_000) { log("[anticaptcha] getBalance: espera 30 s entre llamadas"); return null }
        lastBalanceAt = System.currentTimeMillis()
        return try {
            val r = call("getBalance", JSONObject().put("clientKey", key()))
            if (r.optInt("errorId") != 0) { log("[anticaptcha] getBalance ERROR ${r.optString("errorCode")}"); null }
            else { log("[anticaptcha] clave válida; saldo=${r.optDouble("balance")} USD"); r.optDouble("balance") }
        } catch (e: Exception) { log("[anticaptcha] getBalance EXCEPCION ${e.javaClass.simpleName}"); null }
    }

    private val spendFile get() = File(filesDir, "captcha_spend.json")
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun spentToday(): Pair<Double, Int> = try {
        val j = JSONObject(spendFile.readText()); if (j.optString("date") == today()) j.optDouble("usd") to j.optInt("count") else 0.0 to 0
    } catch (_: Exception) { 0.0 to 0 }
    private fun addSpend(usd: Double) { val (u, n) = spentToday(); spendFile.writeText(JSONObject().put("date", today()).put("usd", u + usd).put("count", n + 1).toString()) }

    /** Una tarea ImageToTextTask. [b64] sin prefijo data: ni saltos de línea. Devuelve null ante cualquier fallo (el llamador decide). */
    suspend fun solve(b64: String, maxLength: Int): Solved? {
        if (!available()) { log("[anticaptcha] sin ANTICAPTCHA_KEY: lo resuelve el humano"); return null }
        val (spent, n) = spentToday()
        if (spent >= capUsd()) { log("[anticaptcha] STOP tope diario alcanzado ($spent de ${capUsd()} USD, $n tareas)"); return null }
        return try {
            val task = JSONObject().put("type", "ImageToTextTask").put("body", b64).put("case", true).put("numeric", 0)
                .put("minLength", 5).put("maxLength", maxLength).put("comment", "Escriba las letras tal como se ven; distingue mayúsculas y minúsculas")
            val created = call("createTask", JSONObject().put("clientKey", key()).put("task", task))
            if (created.optInt("errorId") != 0) { log("[anticaptcha] createTask ERROR ${created.optString("errorCode")}"); return null }
            val id = created.getLong("taskId")
            log("[anticaptcha] tarea creada id=$id; esperando a que la resuelvan")
            val t0 = System.currentTimeMillis()
            delay(5_000)
            while (System.currentTimeMillis() - t0 < 120_000) {
                val r = call("getTaskResult", JSONObject().put("clientKey", key()).put("taskId", id))
                if (r.optInt("errorId") != 0) { log("[anticaptcha] getTaskResult ERROR ${r.optString("errorCode")}"); return null }
                if (r.optString("status") == "ready") {
                    val text = r.getJSONObject("solution").optString("text"); val cost = r.optDouble("cost", 0.0)
                    addSpend(cost)
                    log("[anticaptcha] resuelto id=$id len=${text.length} cost=$cost USD en ${(System.currentTimeMillis() - t0) / 1000}s")
                    return Solved(text, id, cost)
                }
                delay(3_000)
            }
            log("[anticaptcha] TIMEOUT 120 s sin resultado (id=$id)"); null
        } catch (e: Exception) { log("[anticaptcha] EXCEPCION ${e.javaClass.simpleName}: ${e.message?.take(80)}"); null }
    }
}
