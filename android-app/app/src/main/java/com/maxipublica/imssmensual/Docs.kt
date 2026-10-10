package com.maxipublica.imssmensual

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/** Captura de documentos del Paso 5: el sitio entrega PDFs como blob (showPDF/showLineCaptura).
 *  Un hook en la página intercepta el blob y lo envía por el puente; aquí se verifica que sea PDF y se guarda en cache privada. */
class DocCapture(private val filler: FormFiller, private val log: (String) -> Unit) {
    private var pending: CompletableDeferred<JSONObject>? = null

    fun onMessage(json: JSONObject) {
        if (json.optString("type") == "DOC" || json.optString("type") == "DOC_ERROR") pending?.let { if (!it.isCompleted) it.complete(json) }
    }

    private fun hex(b: ByteArray) = b.take(8).joinToString("") { "%02x".format(it) }
    private fun isPdf(b: ByteArray) = b.size > 4 && b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'D'.code.toByte() && b[3] == 'F'.code.toByte()

    /** Hace clic en la columna [header] y espera el blob. Devuelve el archivo PDF verificado o null (todo o nada: nunca guarda algo que no sea PDF). */
    suspend fun fetch(header: String, outDir: File): File? {
        val d = CompletableDeferred<JSONObject>(); pending = d
        val step = filler.runStep("click:$header", FillScripts.clickColumn(header))
        if (step?.optBoolean("ok") != true) return null
        val doc = withTimeoutOrNull(30_000) { d.await() }
        if (doc == null) { log("[$header] FALLO timeout: no llegó el documento"); return null }
        if (doc.optString("type") == "DOC_ERROR") { log("[$header] FALLO ${doc.optString("message")}"); return null }
        var bytes = Base64.decode(doc.optString("b64"), Base64.DEFAULT)
        log("[$header] DOC recibido name=${doc.optString("name")} mime=${doc.optString("mime")} size=${doc.optInt("size")} head=${hex(bytes)}")
        if (!isPdf(bytes)) {
            // el blob viene como text/plain: ¿es un PDF en base64 dentro del texto?
            val txt = String(bytes, Charsets.ISO_8859_1).trim()
            if (txt.isNotEmpty() && Regex("^[A-Za-z0-9+/=\\s]+$").matches(txt)) {
                bytes = try { Base64.decode(txt, Base64.DEFAULT) } catch (_: Exception) { bytes }
                log("[$header] contenido era texto base64 → decodificado size=${bytes.size} head=${hex(bytes)}")
            }
        }
        if (!isPdf(bytes)) { log("[$header] FALLO no_es_pdf (cabecera ${hex(bytes)})"); return null }
        outDir.mkdirs()
        val name = doc.optString("name").replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "documento.pdf" }
        val f = File(outDir, name); f.writeBytes(bytes)
        log("[$header] OK PDF guardado ${f.name} bytes=${bytes.size}")
        return f
    }
}
