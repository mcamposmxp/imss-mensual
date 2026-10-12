package com.maxipublica.imssmensual

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class FieldDef(
    val key: String, val label: String, val required: Boolean = false, val maxLen: Int = 60,
    val digits: Boolean = false, val decimal: Boolean = false, val upper: Boolean = false, val email: Boolean = false,
    val regex: Regex? = null, val error: String = "Formato inválido",
    val choices: List<Pair<String, String>> = emptyList(), val default: String = "",
)

/** Campos = los que piden los pasos del sitio (ver docs/csv-schema.md). El nombre para mostrar es local: el sitio no lo pide. */
object Forms {
    private val CURP = Regex("^[A-Z]{4}\\d{6}[HM][A-Z]{5}[A-Z0-9]\\d$")
    private val MAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    val employer = listOf(
        FieldDef("alias", "Nombre para mostrar (solo en esta app)", required = true, maxLen = 40),
        FieldDef("curp", "CURP del patrón", true, 18, upper = true, regex = CURP, error = "CURP de 18 caracteres"),
        FieldDef("correo", "Correo electrónico", true, 200, email = true, regex = MAIL, error = "Correo no válido"),
        FieldDef("tel_celular", "Teléfono celular (Paso 2)", true, 10, digits = true, regex = Regex("^\\d{10}$"), error = "10 dígitos"),
        FieldDef("correo_alterno", "Correo alternativo (opcional)", false, 200, email = true, regex = MAIL, error = "Correo no válido"),
        FieldDef("cp", "Código postal", true, 5, digits = true, regex = Regex("^\\d{5}$"), error = "5 dígitos"),
        FieldDef("colonia", "Colonia (como aparece en la lista)", true, 60),
        FieldDef("calle", "Calle", true, 50),
        FieldDef("num_ext", "Número exterior", true, 10),
        FieldDef("num_int", "Número interior (opcional)", false, 10),
        FieldDef("subdelegacion", "Subdelegación (como aparece en la lista)", true, 60),
    )

    val worker = listOf(
        FieldDef("alias", "Nombre para mostrar (solo en esta app)", required = true, maxLen = 40),
        FieldDef("curp_trab", "CURP del empleado", true, 18, upper = true, regex = CURP, error = "CURP de 18 caracteres"),
        FieldDef("nss", "NSS", true, 11, digits = true, regex = Regex("^\\d{11}$"), error = "11 dígitos"),
        FieldDef("correo_trab", "Correo electrónico (opcional)", false, 200, email = true, regex = MAIL, error = "Correo no válido"),
        FieldDef("cp_trab", "Código postal", true, 5, digits = true, regex = Regex("^\\d{5}$"), error = "5 dígitos"),
        FieldDef("colonia_trab", "Colonia (como aparece en la lista)", true, 60),
        FieldDef("calle_trab", "Calle", true, 50),
        FieldDef("num_ext_trab", "Número exterior", true, 10),
        FieldDef("num_int_trab", "Número interior (opcional)", false, 10),
        FieldDef("mes", "Mes de incorporación", choices = listOf("actual" to "Mes actual", "siguiente" to "Mes siguiente"), default = "actual"),
        FieldDef("tipo_pago", "Tipo de pago", choices = listOf("dia" to "Pago por día", "mes" to "Pago mes completo"), default = "dia"),
        FieldDef("salario_diario", "Salario diario", true, 9, digits = true, decimal = true, regex = Regex("^\\d{1,6}(\\.\\d{1,2})?$"), error = "Importe válido, p. ej. 331 o 331.50"),
        FieldDef("dias", "Días a marcar en el calendario (mín. 21 por día)", true, 2, digits = true, regex = Regex("^\\d{1,2}$"), default = "21", error = "Número de días"),
    )

    val employerKeys = employer.map { it.key }.filter { it != "alias" }.toSet()
    val workerKeys = worker.map { it.key }.filter { it != "alias" }.toSet()

    fun validate(defs: List<FieldDef>, v: Map<String, String>): Map<String, String> {
        val e = mutableMapOf<String, String>()
        defs.forEach { d ->
            val x = v[d.key].orEmpty().trim()
            if (d.choices.isEmpty()) {
                if (d.required && x.isEmpty()) e[d.key] = "Obligatorio"
                else if (x.isNotEmpty() && d.regex != null && !d.regex.matches(x)) e[d.key] = d.error
            }
        }
        if (v["tipo_pago"] == "dia" && defs.any { it.key == "dias" } && (v["dias"]?.toIntOrNull() ?: 0) < 21) e["dias"] = "Mínimo 21 días para pago por día"
        return e
    }
}

private data class FormState(val kind: String, val id: String?, val parentId: String?, val initial: Map<String, String>)

@Composable
fun DataScreen(model: DataModel, onContinue: () -> Unit) {
    var form by remember { mutableStateOf<FormState?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, Rec>?>(null) }   // ("employer"|"worker", registro)
    val d = model.data
    val compact = PaddingValues(horizontal = 6.dp)

    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = MaterialTheme.colorScheme.background) {
        val f = form
        if (f != null) {
            RecordForm(
                title = (if (f.id == null) "Agregar " else "Editar ") + if (f.kind == "employer") "patrón" else "empleado",
                defs = if (f.kind == "employer") Forms.employer else Forms.worker, initial = f.initial,
                onCancel = { form = null },
                onSave = { values ->
                    if (f.kind == "employer") {
                        val id = model.upsertEmployer(f.id, values)
                        if (f.id == null && d.activeEmployer == null) model.setActive(id, null)
                    } else {
                        val id = model.upsertWorker(f.id, f.parentId!!, values)
                        if (f.id == null) model.setActive(f.parentId, id)
                    }
                    form = null
                },
            )
        } else {
            val orphans = d.workers.filter { w -> d.employers.none { it.id == w.parentId } }
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("Datos para el llenado", style = MaterialTheme.typography.titleMedium)
                Text("Elige el par Patrón → Empleado con el que se llenará el trámite. Los datos se guardan cifrados solo en este teléfono.", fontSize = 12.sp)
                Text(model.activeLabel() ?: "Ningún par seleccionado", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(d.employers, key = { it.id }) { emp ->
                        val workers = d.workers.filter { it.parentId == emp.id }
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Patrón: ${emp.alias}", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { form = FormState("employer", emp.id, null, emp.fields) }, contentPadding = compact) { Text("Editar", fontSize = 12.sp) }
                                    TextButton(onClick = { confirmDelete = "employer" to emp }, contentPadding = compact) { Text("Borrar", fontSize = 12.sp) }
                                }
                                if (workers.isEmpty()) PairRow("→ (sin empleado)", d.activeEmployer == emp.id && d.activeWorker == null, { model.setActive(emp.id, null) }, null, null)
                                workers.forEach { w ->
                                    PairRow("→ Empleado: ${w.alias}", d.activeWorker == w.id,
                                        { model.setActive(emp.id, w.id) },
                                        { form = FormState("worker", w.id, emp.id, w.fields) }, { confirmDelete = "worker" to w })
                                }
                                TextButton(onClick = { form = FormState("worker", null, emp.id, emptyMap()) }, contentPadding = compact) { Text("+ Agregar empleado", fontSize = 12.sp) }
                            }
                        }
                    }
                    if (orphans.isNotEmpty()) item {
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text("Empleados sin patrón", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Asígnalos a un patrón para poder usarlos.", fontSize = 11.sp)
                                orphans.forEach { w ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("→ ${w.alias}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { form = FormState("worker", w.id, null, w.fields) }, contentPadding = compact) { Text("Editar", fontSize = 12.sp) }
                                        TextButton(onClick = { confirmDelete = "worker" to w }, contentPadding = compact) { Text("Borrar", fontSize = 12.sp) }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Mover a:", fontSize = 11.sp)
                                        d.employers.forEach { e -> TextButton(onClick = { model.moveWorker(w.id, e.id) }, contentPadding = compact) { Text(e.alias.take(18), fontSize = 12.sp) } }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { form = FormState("employer", null, null, emptyMap()) }) { Text("Agregar patrón") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onContinue, enabled = d.activeEmployer != null) { Text("Continuar") }
                }
            }
        }
    }

    confirmDelete?.let { (kind, rec) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Borrar") },
            text = { Text(if (kind == "employer") "Se borrará el patrón «${rec.alias}» y todos sus empleados. No se puede deshacer." else "Se borrará el empleado «${rec.alias}». No se puede deshacer.") },
            confirmButton = { TextButton(onClick = { if (kind == "employer") model.deleteEmployer(rec.id) else model.deleteWorker(rec.id); confirmDelete = null }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun PairRow(text: String, selected: Boolean, onSelect: () -> Unit, onEdit: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val compact = PaddingValues(horizontal = 6.dp)
    Row(Modifier.fillMaxWidth().clickable { onSelect() }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onEdit != null) TextButton(onClick = onEdit, contentPadding = compact) { Text("Editar", fontSize = 12.sp) }
        if (onDelete != null) TextButton(onClick = onDelete, contentPadding = compact) { Text("Borrar", fontSize = 12.sp) }
    }
}

@Composable
private fun RecordForm(title: String, defs: List<FieldDef>, initial: Map<String, String>, onSave: (Map<String, String>) -> Unit, onCancel: () -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply { defs.forEach { put(it.key, initial[it.key] ?: it.default) } } }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            defs.forEach { d ->
                if (d.choices.isNotEmpty()) {
                    Text(d.label, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.choices.forEach { (v, l) -> FilterChip(selected = values[d.key] == v, onClick = { values[d.key] = v }, label = { Text(l, fontSize = 12.sp) }) }
                    }
                } else {
                    OutlinedTextField(
                        value = values[d.key].orEmpty(),
                        onValueChange = { nv ->
                            var x = nv
                            if (d.digits) x = x.filter { it.isDigit() || (d.decimal && it == '.') }
                            if (d.upper) x = x.uppercase()
                            values[d.key] = x.take(d.maxLen)
                        },
                        label = { Text(d.label + if (d.required) " *" else "", fontSize = 12.sp) },
                        singleLine = true, isError = errors.containsKey(d.key),
                        supportingText = { errors[d.key]?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = if (d.digits) KeyboardType.Number else if (d.email) KeyboardType.Email else KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("Cancelar") }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                val v = values.toMap().mapValues { it.value.trim() }
                errors = Forms.validate(defs, v)
                if (errors.isEmpty()) onSave(v)
            }) { Text("Guardar") }
        }
    }
}
