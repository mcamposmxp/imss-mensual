package com.maxipublica.imssmensual

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Registro genérico: [fields] usa los mismos nombres de columna que los mapeos (curp, correo, cp, …). */
data class Rec(val id: String, val parentId: String?, val fields: Map<String, String>) {
    val alias get() = fields["alias"].orEmpty()
}

data class Data(
    val employers: List<Rec> = emptyList(),   // patrones
    val workers: List<Rec> = emptyList(),     // empleados (parentId = id del patrón)
    val activeEmployer: String? = null,
    val activeWorker: String? = null,
)

/** Almacén cifrado: AES-GCM con una clave del Android Keystore (no exportable). Archivo files/datos.enc = [iv_len][iv][cifrado]. */
class DataStore(private val dir: File, private val log: (String) -> Unit) {
    private val alias = "imss_datos_v1"
    private val file get() = File(dir, "datos.enc")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return kg.generateKey()
    }

    fun load(): Data {
        if (!file.exists()) return Data()
        return try {
            val raw = file.readBytes()
            val ivLen = raw[0].toInt()
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            fromJson(JSONObject(String(c.doFinal(raw.copyOfRange(1 + ivLen, raw.size)), Charsets.UTF_8)))
        } catch (e: Exception) {
            // No se sobrescribe en silencio: se aparta el archivo ilegible y se avisa.
            log("DATOS: no se pudo descifrar (${e.javaClass.simpleName}); se aparta como datos.enc.bak")
            file.renameTo(File(dir, "datos.enc.bak")); Data()
        }
    }

    fun save(d: Data) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.doFinal(toJson(d).toString().toByteArray(Charsets.UTF_8))
        val tmp = File(dir, "datos.enc.tmp")
        tmp.writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + enc)
        tmp.renameTo(file)
    }

    private fun recJson(r: Rec) = JSONObject().put("id", r.id).put("parent", r.parentId ?: JSONObject.NULL).put("fields", JSONObject(r.fields as Map<*, *>))
    private fun recFrom(j: JSONObject) = Rec(j.getString("id"), j.optString("parent").takeIf { it.isNotEmpty() && it != "null" },
        j.getJSONObject("fields").let { f -> f.keys().asSequence().associateWith { f.getString(it) } })

    private fun toJson(d: Data) = JSONObject()
        .put("employers", JSONArray(d.employers.map { recJson(it) }))
        .put("workers", JSONArray(d.workers.map { recJson(it) }))
        .put("activeEmployer", d.activeEmployer ?: JSONObject.NULL).put("activeWorker", d.activeWorker ?: JSONObject.NULL)

    private fun fromJson(j: JSONObject) = Data(
        (0 until j.getJSONArray("employers").length()).map { recFrom(j.getJSONArray("employers").getJSONObject(it)) },
        (0 until j.getJSONArray("workers").length()).map { recFrom(j.getJSONArray("workers").getJSONObject(it)) },
        j.optString("activeEmployer").takeIf { it.isNotEmpty() && it != "null" },
        j.optString("activeWorker").takeIf { it.isNotEmpty() && it != "null" },
    )
}

class DataModel(dir: File, private val log: (String) -> Unit) {
    private val store = DataStore(dir, log)
    var data by mutableStateOf(store.load())
        private set

    private fun commit(d: Data) { data = d; store.save(d) }
    private fun newId() = UUID.randomUUID().toString()

    fun upsertEmployer(id: String?, fields: Map<String, String>): String {
        val rid = id ?: newId(); val rec = Rec(rid, null, fields)
        commit(data.copy(employers = if (data.employers.any { it.id == rid }) data.employers.map { if (it.id == rid) rec else it } else data.employers + rec))
        return rid
    }

    fun upsertWorker(id: String?, employerId: String, fields: Map<String, String>): String {
        val rid = id ?: newId(); val rec = Rec(rid, employerId, fields)
        commit(data.copy(workers = if (data.workers.any { it.id == rid }) data.workers.map { if (it.id == rid) rec else it } else data.workers + rec))
        return rid
    }

    fun deleteEmployer(id: String) = commit(data.copy(
        employers = data.employers.filter { it.id != id }, workers = data.workers.filter { it.parentId != id },
        activeEmployer = data.activeEmployer.takeIf { it != id }, activeWorker = data.activeWorker.takeIf { data.workers.firstOrNull { w -> w.id == it }?.parentId != id }))

    fun deleteWorker(id: String) = commit(data.copy(workers = data.workers.filter { it.id != id }, activeWorker = data.activeWorker.takeIf { it != id }))

    /** Asigna a un patrón un empleado que quedó sin patrón (creado con una versión anterior de la pantalla). */
    fun moveWorker(workerId: String, employerId: String) =
        commit(data.copy(workers = data.workers.map { if (it.id == workerId) it.copy(parentId = employerId) else it }))

    fun setActive(employerId: String, workerId: String?) = commit(data.copy(activeEmployer = employerId, activeWorker = workerId))

    fun activeLabel(): String? {
        val e = data.employers.firstOrNull { it.id == data.activeEmployer } ?: return null
        val w = data.workers.firstOrNull { it.id == data.activeWorker }
        return "Patrón: ${e.alias} → Empleado: ${w?.alias ?: "(sin empleado)"}"
    }

    private val meses = listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")

    /** Fila para el llenado: campos del patrón + del empleado (+ nombre del mes en español calculado de «actual/siguiente»). */
    fun activeRow(): Map<String, String>? {
        val e = data.employers.firstOrNull { it.id == data.activeEmployer } ?: return null
        val w = data.workers.firstOrNull { it.id == data.activeWorker }
        val row = e.fields.toMutableMap()
        if (w != null) {
            row.putAll(w.fields.filterKeys { it != "alias" })
            val base = java.time.LocalDate.now().let { if (w.fields["mes"] == "siguiente") it.plusMonths(1) else it }
            row["mes_nombre"] = meses[base.monthValue - 1]
        }
        return row
    }

    /** Importa filas de un CSV (una fila = un patrón y, si trae curp_trab, su empleado). Para pruebas por adb. */
    fun importCsv(text: String): Int {
        var n = 0
        CsvSource.parse(text).forEach { row ->
            val emp = row.filterKeys { it in Forms.employerKeys }.toMutableMap()
            emp["alias"] = row["alias_patron"].orEmpty().ifEmpty { "Patrón importado" }
            val eid = upsertEmployer(null, emp)
            var wid: String? = null
            if (row["curp_trab"].orEmpty().isNotEmpty()) {
                val wf = row.filterKeys { it in Forms.workerKeys }.toMutableMap()
                wf["alias"] = row["alias_empleado"].orEmpty().ifEmpty { "Empleado importado" }
                wid = upsertWorker(null, eid, wf)
            }
            if (n == 0) setActive(eid, wid)
            n++
        }
        log("DATOS: importadas $n filas")
        return n
    }
}
