package com.maxipublica.imssmensual

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

const val START_URL = "https://adodigital.imss.gob.mx/pth/editarTrabajador.do"
private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val PREFS = "imss_prefs"
private const val PREF_DESKTOP_UA = "desktop_ua"

class MainActivity : ComponentActivity() {

    private companion object { var activeProbeReceiver: Pair<MainActivity, android.content.BroadcastReceiver>? = null }

    private var webView: WebView? = null
    private val dataModel by lazy { DataModel(filesDir) { AppLog.log(it) } }
    private var showData by mutableStateOf(true)   // al abrir la app aparece el selector de datos
    private var fillActive = false   // candado: evita llenados simultáneos (se chequea en el hilo UI)
    private val formFiller = FormFiller(
        webView = { webView },
        log = { AppLog.log(it) },
        hideKeyboard = {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            webView?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        },
    )

    /** Pasos disponibles = assets paso-*.json (sin .map.json), ordenados; título = campo "title" del esquema. */
    private fun fillSteps(): List<Pair<String, String>> =
        (assets.list("") ?: emptyArray()).filter { Regex("paso-.*\\.json").matches(it) && !it.endsWith(".map.json") }.sorted().map {
            val id = it.removeSuffix(".json")
            id to try { org.json.JSONObject(asset(it)).optString("title").ifEmpty { id } } catch (_: Exception) { id }
        }

    private val docCapture = DocCapture(formFiller) { AppLog.log(it) }
    private val antiCaptcha by lazy { AntiCaptcha(filesDir) { AppLog.log(it) } }
    private var captchaImg: kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>? = null

    /** Resuelve el captcha de imagen con el servicio y lo escribe en el campo (verificando el valor releído). Sin clave → lo hace el humano. */
    private suspend fun solveCaptchaField(f: org.json.JSONObject): Boolean {
        if (!antiCaptcha.available()) { AppLog.log("[captcha] sin ANTICAPTCHA_KEY: lo resuelve el humano"); return true }
        val labels = f.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }
        val dom = f.optJSONObject("dom")
        val d = kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>(); captchaImg = d
        webView?.post { webView?.evaluateJavascript(FillScripts.captchaImage(labels, dom?.optString("id").orEmpty()), null) }
        val img = kotlinx.coroutines.withTimeoutOrNull(10_000) { d.await() }
        if (img == null || !img.optBoolean("ok")) { AppLog.log("[captcha] FALLO captura de imagen: ${img?.optString("reason") ?: "timeout"}"); return false }
        AppLog.log("[captcha] imagen capturada ${img.optInt("w")}x${img.optInt("h")}")
        val solved = antiCaptcha.solve(img.getString("b64"), dom?.optInt("maxlength", 10) ?: 10) ?: return false
        val r = formFiller.runStep(f.getString("key"), FillScripts.text(f.getString("key"), labels, dom?.optString("id").orEmpty(), solved.text))
        return r?.optBoolean("ok") == true
    }
    private var confirmDocs by mutableStateOf<String?>(null)   // null | "mail" | "whatsapp"

    private fun mailTo(): String = try {
        org.json.JSONObject(java.io.File(filesDir, "config.json").readText()).optString("mail_to")
    } catch (_: Exception) { "" }

    /** Paso 5: descarga Comprobante y Línea de captura (todo o nada) y abre el correo de Android con ambos adjuntos.
     *  NO envía: el humano pulsa Enviar en su app de correo (P6). */
    fun downloadAndSendDocs(mode: String = "mail") {
        if (fillActive) { AppLog.log("DOCS ignorado: ya hay una operación activa"); return }
        fillActive = true
        lifecycleScope.launch {
            try {
                val dir = java.io.File(cacheDir, "docs").apply { deleteRecursively(); mkdirs() }
                // Solo controles (estables): el texto de títulos cambia con el diseño responsivo (UA móvil/PC)
                val ready = ProbeScripts.ready("", listOf("[onclick*='showPDF']", "[onclick*='showLineCaptura']"))
                if (!formFiller.waitReady(ready)) { AppLog.log("[docs] FALLO pantalla_desconocida: no es el Paso 5 (faltan controles showPDF/showLineCaptura). ${formFiller.lastReady}"); return@launch }
                webView?.evaluateJavascript(ProbeScripts.CAPTURE, null); kotlinx.coroutines.delay(500)
                val files = mutableListOf<java.io.File>()
                for (h in listOf("Comprobante", "Línea de captura")) {
                    val f = docCapture.fetch(h, dir)
                    if (f == null) { AppLog.log("[docs] STOP: falló '$h'. No se envía nada (todo o nada)."); return@launch }
                    files.add(f)
                }
                val uris = files.map { androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", it) }
                if (mode == "whatsapp") {
                    fun waIntent(pkg: String) = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "application/pdf"
                        setPackage(pkg)
                        putExtra(android.content.Intent.EXTRA_TEXT, "Comprobante y línea de captura IMSS")
                        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                        clipData = ClipData.newRawUri("docs", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val installed = listOf("com.whatsapp", "com.whatsapp.w4b").filter {
                        try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
                    }
                    if (installed.isEmpty()) { AppLog.log("[docs] FALLO WhatsApp no está instalado"); return@launch }
                    val chooser = android.content.Intent.createChooser(waIntent(installed[0]), "Enviar por WhatsApp")
                    if (installed.size > 1) chooser.putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(waIntent(installed[1])))
                    AppLog.log("[docs] OK ${files.size} PDF listos; abriendo WhatsApp (${installed.joinToString()}). El humano elige contacto y pulsa Enviar.")
                    fillActive = false
                    startActivity(chooser)
                } else {
                    val to = mailTo()
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                        // Selector mailto: restringe el chooser a apps de correo (sin WhatsApp, Bluetooth, etc.)
                        selector = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply { data = android.net.Uri.parse("mailto:") }
                        if (to.isNotEmpty()) putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(to))
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Comprobante y línea de captura IMSS")
                        putExtra(android.content.Intent.EXTRA_TEXT, "Adjunto el comprobante y la línea de captura.")
                        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                        clipData = ClipData.newRawUri("docs", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    AppLog.log("[docs] OK ${files.size} PDF listos; abriendo correo para ${if (to.isEmpty()) "(sin destinatario)" else "destinatario configurado"}. El humano pulsa Enviar.")
                    fillActive = false
                    startActivity(android.content.Intent.createChooser(intent, "Enviar documentos"))
                }
            } catch (e: Exception) {
                AppLog.log("[docs] EXCEPCION ${e.javaClass.simpleName}: ${e.message}")
            } finally { fillActive = false }
        }
    }

    private fun asset(name: String) = assets.open(name).bufferedReader().use { it.readText() }

    /** Llena el paso 1 con la fila 1 de files/input.csv. NO captura captcha ni pulsa Continuar. */
    fun fillStep(id: String) {
        if (fillActive) { AppLog.log("FILL ignorado: ya hay un llenado activo"); return }
        val row: Map<String, String>? = dataModel.activeRow() ?: if (BuildConfig.DEBUG) {
            java.io.File(filesDir, "input.csv").takeIf { it.exists() }?.let { CsvSource.parse(it.readText()).firstOrNull() }
        } else null
        if (row == null) { AppLog.log("FILL FALLO: no hay un par Patrón → Empleado seleccionado"); showData = true; return }
        fillActive = true
        formFiller.captchaSolver = { f -> solveCaptchaField(f) }
        lifecycleScope.launch {
            try {
                val schema = org.json.JSONObject(asset("$id.json"))
                val mapping = org.json.JSONObject(asset("$id.map.json"))
                AppLog.log("FILL inicio $id (${dataModel.activeLabel() ?: "respaldo CSV de pruebas"})")
                formFiller.fill(schema, mapping, row)
            } catch (e: Exception) {
                AppLog.log("FILL EXCEPCION ${e.javaClass.simpleName}: ${e.message}")
            } finally { fillActive = false }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView, desktopUa: Boolean) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = if (desktopUa) DESKTOP_UA else null
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient = object : WebViewClient() {
            // Nunca reescribir URLs (L11)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

            // Traza de red de SOLO LECTURA: método + host + ruta (sin query, sin cookies ni cabeceras)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = request.url
                val path = u.path.orEmpty()
                if (!Regex(".*\\.(js|css|png|jpe?g|gif|svg|woff2?|ttf|ico|map)$", RegexOption.IGNORE_CASE).matches(path)) {
                    AppLog.log("NET ${request.method} ${u.host}$path")
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                AppLog.log("PAGE_FINISHED url=$url")
                CookieManager.getInstance().flush() // persiste la sesión del WebView
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    AppLog.log("PAGE_ERROR code=${error.errorCode} desc=${error.description} url=${request.url}")
                }
            }
        }
        wv.settings.setSupportMultipleWindows(true)
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.setDownloadListener { url, _, cd, mime, len ->
            AppLog.log("DOWNLOAD mime=$mime len=$len disp=${cd?.take(80)} url=${url.substringBefore('?').take(160)}")
        }
        wv.webChromeClient = object : WebChromeClient() {
            // Ventanas nuevas (window.open): se registran y se cargan en un WebView hijo sin mostrarse
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                AppLog.log("WINDOW_OPEN gesture=$isUserGesture")
                val child = WebView(this@MainActivity)
                child.settings.javaScriptEnabled = true
                child.settings.domStorageEnabled = true
                child.setDownloadListener { url, _, cd, mime, len ->
                    AppLog.log("DOWNLOAD(child) mime=$mime len=$len disp=${cd?.take(80)} url=${url.substringBefore('?').take(160)}")
                }
                child.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        AppLog.log("WINDOW_OPEN url=${r.url.toString().substringBefore('?').take(160)}"); return false
                    }
                    override fun onPageFinished(v: WebView, url: String?) {
                        AppLog.log("CHILD_PAGE_FINISHED url=${url?.substringBefore('?')?.take(160)}")
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
        }
        // Único canal JS→Kotlin: JSON con campo "type"
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(data: String) {
                val type = try { org.json.JSONObject(data).optString("type") } catch (_: Exception) { "" }
                if (type == "DOC" || type == "CAPTCHA_IMG") AppLog.log("BRIDGE $type (${data.length} car.)") else AppLog.log("BRIDGE $data")   // nunca volcar el contenido
                saveProbe(data)
                try { val j = org.json.JSONObject(data); formFiller.onMessage(j); docCapture.onMessage(j); if (type == "CAPTCHA_IMG") captchaImg?.let { if (!it.isCompleted) it.complete(j) } } catch (_: Exception) {}
            }
        }, "AppBridge")
    }

    /** Guarda el resultado completo de una sonda en files/probes/<type>-<ts>.json (logcat trunca ≈4 KB). */
    private fun saveProbe(data: String) {
        try {
            val type = org.json.JSONObject(data).optString("type")
            if (!type.startsWith("PROBE_")) return
            val dir = java.io.File(filesDir, "probes").apply { mkdirs() }
            java.io.File(dir, "$type.json").writeText(AppLog.mask(data))
        } catch (_: Exception) {}
    }

    fun runProbe(name: String) {
        val script = ProbeScripts.ALL[name]
        if (script == null) { AppLog.log("PROBE desconocida: $name"); return }
        AppLog.log("PROBE run $name")
        webView?.evaluateJavascript(script, null)
    }

    private var probeReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (BuildConfig.DEBUG) {
            // Un solo receptor activo por proceso (L10)
            activeProbeReceiver?.let { (act, old) -> try { act.unregisterReceiver(old) } catch (_: Exception) {} }
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) {
                    when (val cmd = i.getStringExtra("cmd").orEmpty()) {
                        "probe" -> runProbe(i.getStringExtra("name").orEmpty())
                        "open" -> webView?.loadUrl(START_URL)
                        "import_csv" -> {
                            val f = java.io.File(filesDir, "import.csv")
                            if (f.exists()) { dataModel.importCsv(f.readText()); f.delete() } else AppLog.log("DATOS: no existe files/import.csv")
                        }
                        "par_list" -> dataModel.data.let { d ->
                            d.employers.forEach { e -> val ws = d.workers.filter { it.parentId == e.id }
                                if (ws.isEmpty()) AppLog.log("PAR ${e.id.take(6)}: Patrón: ${e.alias} → (sin empleado)")
                                else ws.forEach { w -> AppLog.log("PAR ${e.id.take(6)}/${w.id.take(6)}: Patrón: ${e.alias} → Empleado: ${w.alias}") } }
                            d.workers.filter { w -> d.employers.none { it.id == w.parentId } }.forEach { AppLog.log("SIN PATRON ${it.id.take(6)}: ${it.alias}") }
                        }
                        "par_set" -> dataModel.data.let { d ->
                            val key = i.getStringExtra("name").orEmpty()   // "<6 car. del patrón>/<6 car. del empleado>" o "<6 car. del patrón>"
                            val e = d.employers.firstOrNull { it.id.startsWith(key.substringBefore("/")) }
                            val w = d.workers.firstOrNull { it.parentId == e?.id && key.contains("/") && it.id.startsWith(key.substringAfter("/")) }
                                ?: d.workers.firstOrNull { it.parentId == e?.id && !key.contains("/") }
                            if (e == null) AppLog.log("par_set: no existe $key") else { dataModel.setActive(e.id, w?.id); showData = false; AppLog.log("PAR activo: ${dataModel.activeLabel()}") }
                        }
                        "captcha_balance" -> lifecycleScope.launch { antiCaptcha.balance() }
                        "docs" -> downloadAndSendDocs(i.getStringExtra("name").orEmpty().ifEmpty { "mail" })
                        "click" -> lifecycleScope.launch {
                            formFiller.runStep("click", FillScripts.clickColumn(i.getStringExtra("name").orEmpty()))
                        }
                        "goto" -> i.getStringExtra("url").orEmpty().let { u ->
                            // solo https del host objetivo (cap. 7: no navegar a otros sitios)
                            if (u.startsWith("https://adodigital.imss.gob.mx/")) webView?.loadUrl(u) else AppLog.log("goto rechazado")
                        }
                        "fill" -> fillStep(i.getStringExtra("name").orEmpty().ifEmpty { "paso-1" })
                        else -> AppLog.log("CMD desconocido: $cmd")
                    }
                }
            }
            activeProbeReceiver = this to r
            probeReceiver = r
            val f = android.content.IntentFilter("com.maxipublica.imssmensual.TEST")
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
        }
        val installedAt = try {
            val t = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(t))
        } catch (_: Exception) { "?" }
        val versionLabel = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · instalada $installedAt"
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        AppLog.log("APP_START $versionLabel sdk=${android.os.Build.VERSION.SDK_INT}")

        setContent {
            var desktopUa by remember { mutableStateOf(prefs.getBoolean(PREF_DESKTOP_UA, true)) }
            var showLog by remember { mutableStateOf(false) }
            confirmDocs?.let { mode ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDocs = null },
                    title = { Text(if (mode == "whatsapp") "Enviar por WhatsApp" else "Enviar por correo") },
                    text = {
                        Text("Se descargarán el Comprobante y la Línea de captura del Paso 5 y se abrirá " +
                            (if (mode == "whatsapp") "WhatsApp para que elijas el contacto" else "tu correo" + (mailTo().takeIf { it.isNotEmpty() }?.let { " para $it" } ?: "")) +
                            ". Tú pulsas Enviar.")
                    },
                    confirmButton = { TextButton(onClick = { confirmDocs = null; downloadAndSendDocs(mode) }) { Text("Continuar") } },
                    dismissButton = { TextButton(onClick = { confirmDocs = null }) { Text("Cancelar") } },
                )
            }
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { webView?.loadUrl(START_URL) }) { Text("Abrir", fontSize = 12.sp) }
                    TextButton(onClick = { webView?.reload() }) { Text("Recargar", fontSize = 12.sp) }
                    TextButton(onClick = {
                        desktopUa = !desktopUa
                        prefs.edit().putBoolean(PREF_DESKTOP_UA, desktopUa).apply()
                        webView?.let { configureWebView(it, desktopUa); it.reload() }
                        AppLog.log("UA desktop=$desktopUa")
                    }) { Text(if (desktopUa) "UA: PC" else "UA: móvil", fontSize = 12.sp) }
                    TextButton(onClick = { showData = true }) { Text("Datos", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    var fillMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { fillMenu = true }) { Text("Llenar ▾", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        androidx.compose.material3.DropdownMenu(expanded = fillMenu, onDismissRequest = { fillMenu = false }) {
                            fillSteps().forEach { (id, title) ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(title) }, onClick = { fillMenu = false; fillStep(id) })
                            }
                            var paso5 by remember { mutableStateOf(false) }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Paso 5. Líneas de pago " + if (paso5) "▾" else "▸") },
                                onClick = { paso5 = !paso5 })
                            if (paso5) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text("      Enviar por correo") },
                                    onClick = { fillMenu = false; paso5 = false; confirmDocs = "mail" })
                                androidx.compose.material3.DropdownMenuItem(text = { Text("      Enviar por WhatsApp") },
                                    onClick = { fillMenu = false; paso5 = false; confirmDocs = "whatsapp" })
                            }
                        }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("Sondas", fontSize = 12.sp) }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ProbeScripts.ALL.keys.forEach { n ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(n) }, onClick = { menu = false; runProbe(n) })
                            }
                        }
                    }
                    TextButton(onClick = { showLog = !showLog }) { Text("Log", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
                Text(
                    versionLabel,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                Text(dataModel.activeLabel() ?: "Sin par seleccionado (botón Datos)", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                AppLog.lines.lastOrNull()?.let {
                    Text(it.take(160), fontSize = 10.sp, color = androidx.compose.ui.graphics.Color(0xFF555555),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), maxLines = 2)
                }
                if (showLog) {
                    Column(Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 8.dp)) {
                        Button(onClick = {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.lines.joinToString("\n")))
                        }) { Text("Copiar registro", fontSize = 12.sp) }
                        LazyColumn { itemsIndexed(AppLog.lines.toList()) { i, l -> Text("$i $l", fontSize = 10.sp) } }
                    }
                }
                // Ancho limitado y centrado (2.8): el sitio decide el layout por ancho CSS
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                        factory = { ctx ->
                            WebView(ctx).also { wv ->
                                webView = wv
                                configureWebView(wv, desktopUa)
                                wv.loadUrl(START_URL)
                            }
                        },
                    )
                }
            }
                if (showData) DataScreen(dataModel) { showData = false }
            }
        }
    }

    // onPause/onResume por instancia; nunca pauseTimers() global (L1)
    override fun onPause() { super.onPause(); if (!fillActive) webView?.onPause() }
    override fun onResume() { super.onResume(); webView?.onResume(); webView?.resumeTimers() }

    override fun onDestroy() {
        probeReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        if (activeProbeReceiver?.first === this) activeProbeReceiver = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
