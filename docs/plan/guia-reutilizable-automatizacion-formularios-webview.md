# Guía reutilizable: automatizar formularios web en un WebView de Android

> ⚠️ **Reemplazada por la [v2](guia-reutilizable-v2-sistema-multipaso-que-aprende.md)** (2026-10-12): trámites multipaso, datos cifrados, ciclo de aprendizaje con evidencia, sesión que caduca, captcha opcional y descarga de PDF. Esta v1 se conserva como historial; en un proyecto nuevo, empieza por la v2.

> **Propósito.** Kit de arranque para un proyecto nuevo en el que una app Android nativa (Kotlin + Jetpack Compose) recibe una **URL inicial** y un **CSV**, descubre el formulario de la página con sondas DOM, lo llena con los datos del CSV, sigue los pasos y URLs siguientes (flujos multipaso) y se detiene ante la acción final irreversible para pedir confirmación humana. Incluye además toda la experiencia de **automatización de pruebas** por adb.
>
> **Origen.** Destila la experiencia de un proyecto real (app Android v0.145, ciclo completo validado en Android 10, 14 y 16). El contenido es **genérico**: no depende de ningún sitio concreto. Los nombres de funciones citados (`FormFiller.runStep`, `TestHarness`, etc.) son del repositorio origen y sirven como referencia de diseño; los fragmentos del Anexo C están generalizados.
>
> **Lectores.** Una IA (Claude Code) que arranca el proyecto y los humanos que la supervisan. Cada capítulo trae *reglas* (imperativas, para la IA), el *porqué* (para humanos) y una *checklist*.

---

## 0. Cómo usar este documento

**Para la IA que arranca el nuevo proyecto:**

1. Lee los capítulos 1, 7 y 10 completos antes de escribir código (arquitectura, seguridad, lecciones).
2. Responde el capítulo 12 con el humano **antes** de empezar (sitio objetivo, login, acción final, volumen).
3. Sigue el plan por fases del capítulo 11. No avances de fase sin cumplir su criterio de salida.
4. Copia los anexos A y B a `.claude/agents/` y a `CLAUDE.md`/`AGENTS.md` del proyecto nuevo y ajusta los marcadores `<...>`.
5. Usa los fragmentos del Anexo C como punto de partida, no como verdad final: verifica cada uno contra el sitio objetivo con las sondas.

**Principios que valen para todo el documento (no negociables):**

| # | Principio |
|---|---|
| P1 | **Sin evidencia no hay «funciona».** Afirmar que un flujo funciona exige leer el log o el JSON de resultado del dispositivo. |
| P2 | **Diagnosticar con datos.** Primero una sonda/log, después el arreglo. Nunca «arreglar» por suposición. |
| P3 | **Localizar por texto de etiqueta, no por clases CSS** (las clases generadas cambian sin aviso). |
| P4 | **Verificar el valor aplicado**, no un efecto colateral (p. ej. «se cerró el diálogo»). |
| P5 | **Cerrar siempre lo que se abre** (diálogos, menús), aunque el paso falle. |
| P6 | **Confirmación humana antes de la acción irreversible** (enviar, publicar, pagar, borrar). |
| P7 | **El oráculo de pass/fail vive en la app**, no en el agente que ejecuta la prueba. |
| P8 | **Probar en más de un dispositivo y tamaño de pantalla.** El ancho CSS decide el layout del sitio. |
| P9 | **Cada build que se instala sube `versionCode`/`versionName`**, para saber que el APK es el nuevo. |
| P10 | **Nunca leer, registrar ni versionar credenciales o la sesión del navegador.** |

---

## 1. Visión y arquitectura

### 1.1 Pipeline

```
URL inicial ──► [1] WebView carga + sesión (login manual una vez)
                 │
                 ▼
             [2] Espera «pantalla lista»  (URL + texto del DOM, 2 lecturas seguidas)
                 │
                 ▼
             [3] SONDAS DOM → FormSchema (campos, tipos, etiquetas, opciones, orden)
                 │
                 ▼
             [4] Mapeo columna CSV → campo   (humano/IA lo aprueba; se guarda como JSON)
                 │
                 ▼
             [5] Motor de llenado: un paso por campo, verificando el valor aplicado
                 │
                 ▼
             [6] ¿Hay siguiente paso/URL?  ── sí ──► volver a [2] con el siguiente Flow-step
                 │ no
                 ▼
             [7] STOP: resumen + confirmación humana de la acción final
```

### 1.2 Componentes (carpeta `app/src/main/java/<paquete>/`)

| Componente | Responsabilidad | Equivalente en el repo origen |
|---|---|---|
| `MainActivity` (Compose) | Hospeda el WebView, barra de acciones, menús (Sondas, Llenado), registro de log, ciclo de vida | `MainActivity.kt` |
| `configureWebView` | Settings, cookies, clientes, puente JS | `MainActivity.configureWebView` |
| `WebBridge` (`@JavascriptInterface postMessage`) | Único canal JS→Kotlin: JSON con campo `type` | puente JS del repo origen |
| `FormFiller` | Orquesta pasos: `runStep(label, script, timeout)` | `FormFiller.kt` |
| `FillScripts` | Scripts JS de llenado (helpers + una función por tipo de campo) | `FormFillScripts.kt` |
| `ProbeScripts` | Sondas de solo lectura | `InjectionScripts.kt` |
| `FlowRunner` (nuevo) | Ejecuta el `Flow` declarativo multipaso | (no existía; ver cap. 6) |
| `CsvSource` | Parser CSV tolerante + mapeos de valores | `CsvInventory.kt`, `Vehicle.kt` |
| `FileChooserClient` | `WebChromeClient` que entrega archivos al `<input type=file>` | `PhotoInjectingWebChromeClient.kt` |
| `FillForegroundService` | Mantiene vivo el proceso durante un llenado | `FillForegroundService.kt` |
| `TestHarness` (solo debug) | `BroadcastReceiver` para ejecutar comandos de prueba por adb | `TestHarness.kt` |
| `scripts/qa/` | Ejecutores bash/python, generadores de matrices, oráculos externos | `scripts/qa/` |

### 1.3 Decisiones firmes de arquitectura

- **Kotlin nativo + WebView.** Razón técnica decisiva: el toque nativo (`dispatchTouchEvent`) cuenta como *user activation* y abre el selector de archivos; un `input.click()` por JS no. Un framework multiplataforma complica esto.
- **Un solo canal JS→Kotlin** (`postMessage` con JSON tipado). Todo script termina enviando exactamente un mensaje `{type, field, ok, message, ...}`.
- **Scripts JS asíncronos autocontenidos** (IIFE `async`) que Kotlin inyecta con `evaluateJavascript` y espera con un `CompletableDeferred` + timeout.
- **Log a logcat con una etiqueta propia** (`<TAG>`) espejando el log de pantalla: es la fuente de evidencia para pruebas.

**Checklist cap. 1:** [ ] Pipeline implementado por etapas independientes · [ ] Un único `postMessage` · [ ] Etiqueta de logcat definida · [ ] Todas las decisiones anteriores escritas en `CLAUDE.md`.

---

## 2. Requerimientos del WebView

### 2.1 Configuración mínima

```kotlin
wv.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = true                    // solo si se usan páginas locales de prueba
    mediaPlaybackRequiresUserGesture = false
    useWideViewPort = true
    loadWithOverviewMode = true
    userAgentString = DESKTOP_UA              // ver 2.2
}
CookieManager.getInstance().apply {
    setAcceptCookie(true)
    setAcceptThirdPartyCookies(wv, true)
}
```

Manifiesto: `INTERNET`; `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` y `POST_NOTIFICATIONS` (pedido en runtime desde Android 13) si hay servicio; `usesCleartextTraffic="false"`; `FileProvider` con `cache-path` para los archivos que se entregarán al `<input type=file>`.

### 2.2 User-Agent
- Hazlo **configurable** (preferencia + opción de menú). Algunos sitios devuelven una vista móvil que queda **en blanco** dentro de un WebView; con UA de escritorio renderizan bien. Empieza con UA de escritorio y ofrece alternar.
- Para descargar archivos desde Kotlin usa un UA propio y **sin `Referer` inventado** (muchos CDN responden 400/403 al detectar hotlinking falso).

### 2.3 Sesión y credenciales
- La sesión persiste en el almacenamiento del WebView. **El login lo hace el humano una vez** dentro de la app; el código nunca ve ni guarda usuario/contraseña. 2FA y captcha los resuelve el humano.
- Si hay `auth/` o similar con datos de sesión, va en `.gitignore` y **la IA no lo lee** (P10).

### 2.4 `WebViewClient`
- `shouldOverrideUrlLoading` → **`false` siempre**. Reescribir URLs provocó un bucle infinito con pantalla en blanco.
- `onPageFinished`: registrar la URL; completar el `CompletableDeferred` de «página cargada» si la URL coincide con la esperada; ejecutar limpiezas específicas de la pantalla (p. ej. cerrar un banner).
- `onReceivedError`: reportar **solo** si `request.isForMainFrame`, para no saturar el log.

### 2.5 Espera de «pantalla lista» (obligatoria)
`onPageFinished` **no** basta: SPAs y sitios con recargas diferidas vuelven a montar el DOM después. Patrón probado:
1. Cargar la URL y esperar `onPageFinished` (≤ 15 s).
2. Sondear `CHECK_READY` cada 500 ms (≤ 20 s). `CHECK_READY` devuelve `ok` si hay **marcadores de la pantalla** (un input/control esperado **y** texto esperado por regex).
3. Exigir **2 lecturas OK consecutivas**.
4. Añadir 500 ms de estabilización.

```js
// CHECK_READY (ajustar marcadores al sitio objetivo)
(function(){
  function send(p){ try{ window.AppBridge.postMessage(JSON.stringify(p)); }catch(e){} }
  var bt = document.body ? (document.body.innerText||'') : '';
  var hasControl = document.querySelectorAll('input[type="file"],input,textarea').length > 0;
  var hasText = /<REGEX_TEXTO_ESPERADO_ES>|<REGEX_TEXTO_ESPERADO_EN>/i.test(bt);
  send({type:'READY_RESULT', ok: hasControl && hasText, url: location.href, bodyLen: bt.length});
  true;
})();
```

### 2.6 Puente JS→Kotlin

```kotlin
wv.addJavascriptInterface(object {
    @JavascriptInterface
    fun postMessage(data: String) {
        onMessage(data)                                   // log + espejo a logcat
        try {
            val json = JSONObject(data)
            when (json.optString("type")) {
                "FILL_RESULT", "FILL_ERROR" -> formFiller.onFillMessage(json)
                "READY_RESULT" -> readyDeferred?.let { if (!it.isCompleted) it.complete(json.optBoolean("ok")) }
                "FILE_LOCATE"  -> handleFileLocate(wv, data)
            }
        } catch (_: Exception) { /* no era JSON de control */ }
    }
}, "AppBridge")
```

### 2.7 Ciclo de vida y segundo plano
- **Nunca `pauseTimers()`**: es global al proceso (afecta a todos los WebViews y timers) y rompió el montaje del formulario. Usa `webView.onPause()`/`onResume()` **por instancia**, y `onPause` solo si no hay llenado activo (`!fillActive`). En `onResume`: `webView.onResume()` + `resumeTimers()`.
- **Nivel A de segundo plano:** al empezar un llenado, `fillActive = true`, arrancar un `ForegroundService` (tipo `dataSync`, `START_NOT_STICKY`) y `resumeTimers()`. Al terminar, liberar todo. `fillActive` es además el **candado de serialización**: evita doble toque y llenados simultáneos (se chequea de forma síncrona en el hilo UI).
- **No** se implementa reanudación por checkpoints si el proceso muere: muchos sitios no guardan borrador. Se reinicia el flujo.
- En **debug**: `FLAG_KEEP_SCREEN_ON`, `setShowWhenLocked(true)`, `setTurnScreenOn(true)`. Con la pantalla apagada el WebView deja de responder y las pruebas fallan de forma engañosa.

### 2.7.1 Manifiesto de la Activity
```xml
android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|uiMode"
android:windowSoftInputMode="adjustNothing"   <!-- o SOFT_INPUT_ADJUST_NOTHING por código -->
```
- Sin `screenLayout` en `configChanges`, girar la pantalla **recrea la Activity** y pierde el llenado.
- `adjustNothing`: el teclado flota sin redimensionar el WebView (redimensionar cambia el layout del sitio a media operación). Tras cada paso, oculta el teclado (`hideSoftInputFromWindow`).

### 2.8 Layout y ancho
El sitio decide su layout por **ancho CSS**. En tableta/horizontal puede servir un diseño de dos columnas donde el contenedor del formulario queda con alto 0. Mitigación: limitar el ancho del WebView (≈ ≤ 720 dp, centrado) y compactar el encabezado nativo. **Mide el umbral con una sonda de geometría**, no lo supongas.

### 2.9 Selector de archivos («cero toques»)
Para formularios con `<input type="file">` (adjuntos, fotos, documentos):
1. **JS localiza** el input (o su contenedor visible, porque el input suele medir 0×0), hace `scrollIntoView({block:'center'})`, **espera 350 ms** a que el scroll se asiente y reporta coordenadas CSS + `devicePixelRatio` + ancho del viewport (`FILE_LOCATE`).
2. **Kotlin convierte** a píxeles físicos con el **`dpr` que reporta el JS** (no `displayMetrics.density`: en algunos equipos no coinciden y el toque cae en otro sitio) y simula un toque nativo (`MotionEvent` DOWN + UP a 80 ms con `dispatchTouchEvent`).
3. El `WebChromeClient.onShowFileChooser` se dispara; descarga los archivos a `cacheDir/...` y los entrega como `content://` vía `FileProvider` en el `filePathCallback`.
4. **Salvaguardas** (aprendidas con un incidente real, un toque cayó sobre un botón de acción):
   - Antes de tocar, JS comprueba con `document.elementFromPoint` que el punto no cae sobre un botón de acción (`/^(enviar|guardar|siguiente|publicar|eliminar|cancelar|submit|save|next|delete)\b/i`); si cae, **no toca** y falla.
   - Kotlin descarta el toque si el ancho del WebView cambió entre localizar y tocar (giro, multiventana), tanto al recibir el mensaje como justo antes de ejecutar.
   - Si `FILE_LOCATE` llega con `ok=false`, la subida falla de inmediato (no esperar el timeout).
5. **Descargas estrictas (todo o nada):** si falla **una** descarga, no se entrega ninguna. Un archivo de respaldo «de prueba» puede sustituir datos reales sin que nadie lo note.
6. Extensiones: fuerza la extensión que el sitio acepta (p. ej. `.jpg`); un archivo con extensión no admitida se rechaza aunque su contenido sea válido.

**Checklist cap. 2:** [ ] UA configurable · [ ] `shouldOverrideUrlLoading=false` · [ ] «pantalla lista» con 2 lecturas · [ ] Sin `pauseTimers` · [ ] `configChanges` completo · [ ] `adjustNothing` · [ ] Foreground service durante el llenado · [ ] `fillActive` · [ ] Subida de archivos con `elementFromPoint` y `dpr` · [ ] Sin credenciales en repo.

---

## 3. Sondas DOM y exploración de formularios

Las sondas son **scripts de solo lectura** que vuelcan la estructura de la pantalla actual para decidir *cómo* llenarla. Son la base de todo: antes de escribir una receta de llenado, existe una sonda que prueba que el control se puede localizar.

### 3.1 Invocación y salida
- Cada sonda es un ítem del menú «Sondas» que llama `webView.evaluateJavascript(SCRIPT, null)`. El resultado vuelve por `postMessage` como JSON con `type` (`PROBE_*`) y se espeja a logcat.
- **Límite de logcat ≈ 4 KB por línea.** Trocea mensajes largos (≈ 15 elementos por mensaje) y, en el modo prueba, guarda el resultado final completo en archivo (`files/test/out/<run>.json`).
- Lectura: `adb -s <IP>:5555 logcat -d -s <TAG>` (limpiar antes con `logcat -c`) o «Copiar registro» dentro de la app.
- Guarda las salidas relevantes en `docs/qa/<fecha>-probe-<tema>.json` como evidencia.

### 3.2 Catálogo de sondas genéricas

| Sonda | Qué extrae | Para qué sirve |
|---|---|---|
| General | URL; conteos (inputs, file, comboboxes, textareas, buttons); lista de inputs `{type, ariaLabel}`; textos de `[role=combobox]`; textos de `[role=button]` | Primer vistazo de una pantalla nueva |
| Campos | Todos los `span` hoja de texto corto (2–29 car., sin duplicados, máx. 60) = **etiquetas candidatas**; inputs de texto con `aria`, `placeholder`, `nearLabel`; toggles `[role=checkbox|switch]`/`input[type=checkbox]` con estado y texto cercano | Descubrir qué campos existen **en cada etapa** de un formulario progresivo |
| Inputs de texto | Cada input/textarea/contenteditable (excluye file, hidden, search, checkbox, radio) con `{tag,type,aria,ce,y,h,nearLabel,value}` **ordenado por Y** | Distinguir inputs sin `aria-label` (Modelo, Precio, Descripción…) |
| Botones | `[role=button]`, `button`, `[aria-label]` con `{texto, aria, visible}` (máx. 40) | Localizar «Siguiente», «Enviar», etc. |
| Geometría | Rect del control objetivo, elementos `sticky/fixed`, contenedores con scroll, `elementFromPoint` | Diagnosticar toques que caen fuera de lugar y layouts por ancho |
| Checkboxes/radios | `input[type=checkbox|radio]`, `[role=checkbox|switch|radio]`, `[aria-checked]` | Los controles tipo switch no siempre son `<input>` |
| Diálogo abierto | Texto y opciones del `[role=dialog|listbox|menu]` abierto | Entender cómo expone opciones un dropdown |
| Opciones de dropdown | Abrir el control y volcar opciones (`role=option`, `listbox *`, `menuitem`, `li`) + atributos aria (`autocomplete`, `role`, `aria-expanded`, `aria-haspopup`) | Elegir la receta (simple, con búsqueda, typeahead) |
| Lectura de formulario | Releer todos los campos y valores actuales | Base del oráculo de verificación |

**Sonda de campos (versión generalizada):**

```js
(function(){
  function send(p){ try{ window.AppBridge.postMessage(JSON.stringify(p)); }catch(e){} }
  function clean(s){ return (s||'').replace(/\s+/g,' ').trim(); }
  try {
    var spans = [].slice.call(document.querySelectorAll('span'))
      .filter(function(s){ return s.children.length===0; })
      .map(function(s){ return clean(s.textContent); })
      .filter(function(t){ return t.length>1 && t.length<30; });
    var seen={}, labels=[]; spans.forEach(function(t){ if(!seen[t]){ seen[t]=1; labels.push(t); } });
    var textInputs = [].slice.call(document.querySelectorAll('input[type="text"],input:not([type]),textarea'))
      .map(function(inp){
        var lbl='', el=inp;
        for (var u=0; u<6 && el && !lbl; u++){
          var sp = el.parentElement ? el.parentElement.querySelectorAll('span') : [];
          for (var k=0;k<sp.length;k++){ var tt=clean(sp[k].textContent);
            if(sp[k].children.length===0 && tt.length>1 && tt.length<30){ lbl=tt; break; } }
          el = el.parentElement;
        }
        return { aria: inp.getAttribute('aria-label')||'', placeholder: inp.getAttribute('placeholder')||'', nearLabel: lbl };
      });
    var toggles = [].slice.call(document.querySelectorAll('[role="checkbox"],[role="switch"],input[type="checkbox"]'))
      .map(function(el){ var lbl='', n=el;
        for (var u=0; u<8 && n && !lbl; u++){ var t=clean(n.innerText||n.textContent||''); if(t.length>2&&t.length<60) lbl=t; n=n.parentElement; }
        return { role: el.getAttribute('role')||el.getAttribute('type')||'', checked: el.getAttribute('aria-checked')||el.checked||'', nearText: lbl.substring(0,60) };
      });
    send({type:'PROBE_FIELDS', url: location.href, labels: labels.slice(0,60), textInputs: textInputs, toggles: toggles});
  } catch(err){ send({type:'PROBE_ERROR', message: String(err && err.message || err)}); }
  true;
})();
```

### 3.3 Hallazgos DOM que condicionan el diseño (genéricos)
- Muchos formularios modernos **no usan `<select>`, ni `role=combobox`, ni `role=option`**: los dropdowns son `div` clicables y al abrirlos aparece un `[role=dialog]` con opciones `div` sin rol. Puede variar entre pantallas del mismo sitio (la pantalla de edición sí puede usar `role=combobox`). **Sondea cada pantalla.**
- Los iconos son **glifos de fuente** (rango `–`): quítalos al leer texto (`stripGlyphs`).
- Los inputs de texto frecuentemente **no tienen `aria-label`**: se identifican por el primer `span` hoja de texto corto al subir por el DOM (`nearLabel`).
- Formularios **progresivos**: aparecen campos nuevos al llenar los anteriores. El orden de llenado importa y cada paso debe esperar a su campo.
- Listas con **carga perezosa**: hay que hacer scroll del contenedor hasta que se estabilice la altura.
- Las etiquetas dependen del **idioma** de la cuenta: pasa **listas bilingües** (`["Año","Year"]`).

### 3.4 De sondas a `FormSchema` (descubrimiento asistido)
El objetivo del nuevo proyecto es aceptar una URL cualquiera. Flujo recomendado:

1. **Explorar:** abrir la URL, esperar «lista» (cap. 2.5), ejecutar *Sonda campos* + *Inputs de texto* + *Botones* + *Checkboxes*.
2. **Clasificar** cada control en un tipo: `text`, `textarea`, `number`, `dropdown`, `dropdown_search`, `typeahead`, `checkbox`, `radio`, `file`, `button`.
   - Para clasificar un dropdown: abrir, ejecutar *Diálogo abierto*/*Opciones*, cerrar (P5). Si el diálogo tiene un `input` de búsqueda → `dropdown_search`. Si las opciones cambian al escribir en el propio input → `typeahead`.
3. **Emitir el `FormSchema`:**

```json
{
  "screen_id": "paso-1",
  "url_pattern": "^https://ejemplo\\.com/alta(\\?.*)?$",
  "ready": { "text_regex": "Tipo de|Adjuntar", "needs": ["input"] },
  "fields": [
    { "key": "year",  "labels": ["Año","Year"], "kind": "dropdown",        "required": true,  "order": 1, "options_sample": ["2025","2024"] },
    { "key": "make",  "labels": ["Marca","Make"], "kind": "dropdown_search", "required": true, "order": 2, "depends_on": "year" },
    { "key": "price", "labels": ["Precio","Price"], "kind": "text",          "required": true,  "order": 3 },
    { "key": "files", "labels": ["Agregar archivos"], "kind": "file",        "required": false, "order": 0 }
  ],
  "actions": [ { "key": "next", "labels": ["Siguiente","Next"], "kind": "button" } ]
}
```
4. **Proponer el mapeo** `columna CSV → key` (por similitud de nombre y por tipo de dato) y **pedir aprobación humana**. Guardarlo en `mappings/<screen_id>.json`:

```json
{ "screen_id": "paso-1",
  "map": { "year": {"csv":"year"}, "make": {"csv":"make"}, "price": {"csv":"price", "transform":"digits"} },
  "value_maps": { "make": { "CHEVY": "Chevrolet" } } }
```
5. **Validar en seco** (`dry_fill`: llenar sin enviar y releer, cap. 8) y solo entonces habilitar el llenado real.

**Regla:** la IA propone, el humano aprueba el mapeo, el motor ejecuta. Un mapeo no aprobado nunca se ejecuta en producción.

**Checklist cap. 3:** [ ] Sondas en el menú · [ ] Salida troceada y a archivo · [ ] `FormSchema` por pantalla · [ ] Mapeo aprobado por humano · [ ] Etiquetas bilingües · [ ] Evidencia guardada en `docs/qa/`.

---

## 4. Motor de llenado

### 4.1 Núcleo: `runStep`

```kotlin
suspend fun runStep(label: String, script: String, timeoutMs: Long = 15_000): Boolean {
    val deferred = CompletableDeferred<JSONObject>()
    pending = deferred
    webView.post { webView.evaluateJavascript(script, null) }
    val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
    lastResult = result
    if (result == null) { onLog("[$label] TIMEOUT (sin respuesta)"); return false }
    val ok = result.optBoolean("ok")
    onLog("[$label] ${if (ok) "OK" else "FALLO"}: ${result.optString("message")}")
    hideKeyboard?.invoke()      // el focus de un input pudo abrir el teclado
    delay(600)                  // el formulario es progresivo y reorganiza el DOM
    return ok
}
fun onFillMessage(json: JSONObject) { pending?.let { if (!it.isCompleted) it.complete(json) } }
```
Cada script JS responde **una sola vez** (`FILL_RESULT` o `FILL_ERROR`).

### 4.2 Helpers JS compartidos (se prepende a cada script)

```js
function send(p){ try{ window.AppBridge.postMessage(JSON.stringify(p)); }catch(e){} }
function clean(s){ return (s||'').replace(/\s+/g,' ').trim(); }
function stripGlyphs(s){ return clean((s||'').replace(/[-]/g,'')); }
function sleep(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }
function fullClick(el){                       // secuencia completa: React/otros frameworks necesitan todos los eventos
  var r=el.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
  var o={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};
  ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){
    var Ev = t.indexOf('pointer')===0 ? PointerEvent : MouseEvent;
    try{ el.dispatchEvent(new Ev(t,o)); }catch(e){}
  });
  try{ el.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true}));
       el.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true})); }catch(e){}
}
function mouseSelect(el){                     // para opciones de lista: añade pointerover/mouseover
  var r=el.getBoundingClientRect(), o={bubbles:true,cancelable:true,view:window,clientX:r.left+r.width/2,clientY:r.top+r.height/2};
  ['pointerover','mouseover','pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){
    var Ev=t.indexOf('pointer')===0?PointerEvent:MouseEvent; try{ el.dispatchEvent(new Ev(t,o)); }catch(e){} });
}
// Etiqueta: UNA (string) o VARIAS (array), nodo hoja, sin distinguir mayúsculas → soporte bilingüe.
function findLabelNode(labels){
  var arr=(typeof labels==='string')?[labels]:labels;
  var wanted=arr.map(function(s){return clean(s).toLowerCase();});
  var all=document.querySelectorAll('span,div,label,h1,h2,h3,strong');
  for (var i=0;i<all.length;i++){ var el=all[i]; if(el.children.length!==0) continue;
    if(wanted.indexOf(clean(el.textContent).toLowerCase())!==-1) return el; }
  return null;
}
// De la etiqueta al contenedor CLICABLE del campo, SIN clases CSS.
function findClickableContainer(node){
  var t=node;
  for (var up=0; up<6; up++){
    if(!t.parentElement) break; t=t.parentElement;
    var role=t.getAttribute&&t.getAttribute('role'), ti=t.getAttribute&&t.getAttribute('tabindex');
    if(role==='button'||role==='combobox'||ti!=null) return t;
    var r=t.getBoundingClientRect();
    if(r.width>window.innerWidth*0.6 && r.height>20 && r.height<120) return t;   // «fila de campo» completa
  }
  return (node.parentElement&&node.parentElement.parentElement)?node.parentElement.parentElement:node;
}
// Panel de la barra del sitio (notificaciones, mensajes, cuenta) que también usa role=dialog/menu.
function isChromePanel(el){
  try{ var t=(el.innerText||'').toLowerCase();
       var bad=['<texto-panel-1>','<texto-panel-2>'];       // ← rellenar con frases de los paneles del sitio
       for (var i=0;i<bad.length;i++) if(t.indexOf(bad[i])!==-1) return true; }catch(e){}
  return false;
}
function findOpenDialog(){
  var all=document.querySelectorAll('[role="dialog"],[role="listbox"],[role="menu"]');
  for (var i=0;i<all.length;i++) if(!isChromePanel(all[i])) return all[i];
  return null;
}
```

### 4.3 Recetas por tipo de campo

**Texto (`fillTextInput`).** Los inputs de frameworks controlados (React) **revierten** el valor si solo asignas `.value`. Usa el setter nativo del prototipo y dispara `InputEvent` carácter a carácter:

```js
async function typeReact(el, val){
  var isCE = el.getAttribute && el.getAttribute('contenteditable')!==null;
  el.focus(); await sleep(80);
  if (isCE){ el.textContent=''; el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward'}));
             el.textContent=val; el.dispatchEvent(new InputEvent('input',{bubbles:true,data:val,inputType:'insertText'}));
             await sleep(50); return el.textContent; }
  var proto = el.tagName==='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  var setter = Object.getOwnPropertyDescriptor(proto,'value').set;
  setter.call(el,''); el.dispatchEvent(new Event('input',{bubbles:true})); await sleep(50);
  for (var i=0;i<val.length;i++){
    setter.call(el, val.substring(0,i+1));
    el.dispatchEvent(new InputEvent('input',{bubbles:true,data:val[i],inputType:'insertText'}));
    if (i%10===0) await sleep(10);
  }
  el.dispatchEvent(new Event('change',{bubbles:true})); await sleep(50);
  return el.value;
}
```
Localización del input, en este orden: `aria-label`/`placeholder` → `nearLabel` exacto. Excluye `file|hidden|search|checkbox|radio` (un `input[type=file]` confundió a «Precio»). Marca el input escrito con `data-filled='1'` para no escribir dos veces en el mismo. Verificación: el valor final contiene los primeros 8 caracteres esperados.

**Dropdown simple (`selectDropdown`)** — secuencia validada:
1. Si hay un diálogo abierto de un paso previo → cerrarlo (Escape + clic fuera).
2. `findLabelNode` → `findClickableContainer` → `fullClick` en la fila.
3. Esperar el diálogo (hasta ≈ 1,8 s) con `findOpenDialog`.
4. Buscar la opción por texto, **sin distinguir mayúsculas**: exacta primero, luego prefijo `valor + ' '`. Si no aparece, hacer scroll del contenedor (hasta 20 veces × 120 ms).
5. `scrollIntoView`, resolver el ancestro clicable (`role=button|option|tabindex`), `fullClick`.
6. Esperar el cierre (10 × 150 ms); si no cierra, forzar el cierre.
7. **Criterio de éxito real (P4):** releer el texto de la fila y comprobar que contiene el valor, o que las palabras de ≥ 4 letras del valor que no están en la etiqueta están todas en la fila. «El diálogo cerró» es solo *fallback*.
8. Si falla: cerrar el diálogo (P5) y devolver un `sample` de 15 opciones para depurar.

**Dropdown con búsqueda (`selectDropdownSearch`).** Abre como un dropdown; dentro aparece un `input` de búsqueda: escribe carácter a carácter con el setter nativo (120 ms/carácter), espera ≈ 600 ms a que filtre, elige la opción (exacta o que contenga el texto) con `mouseSelect` y reintenta sobre el contenedor si el diálogo sigue abierto. Verifica igual que el simple.

**Typeahead (autocompletado remoto, p. ej. ubicación).** El texto escrito **no basta**: hay que **hacer clic en una sugerencia**. Estrategia:
- Genera varias consultas ordenadas de más a menos específica (completa, sin complementos, primeras palabras, última palabra larga, «ciudad, región», código postal, región sola).
- Tras cada carácter espera a que la lista **se estabilice**: misma firma 4 veces seguidas (hasta 40 × 200 ms). La primera lista puede pertenecer a la consulta anterior.
- Puntúa opciones normalizando sin tildes ni palabras vacías; acepta solo coincidencias que incluyan el contexto (región/país); **nunca** elijas una opción de otro país/contexto.
- Selecciona con `mouseSelect` sobre el hijo `div` y reintenta sobre el contenedor.
- Si no hay sugerencia aceptable: falla devolviendo `options` y `queries` probadas. Ofrece un respaldo (entidad cercana ya resuelta por coordenadas, con límite de distancia).
- Mantén una **bitácora persistente** de los casos no resueltos y un mecanismo de equivalencias manuales.

**Checkbox/switch (`setCheckbox`).** Busca `[role=checkbox]`, `[role=switch]`, `input[type=checkbox]`; toma el texto del primer ancestro con > 2 caracteres como etiqueta; haz scroll hasta 8 × 500 px si no aparece; lee `aria-checked`/`.checked` y **solo haz clic si el estado difiere** del deseado; reintenta sobre el padre. **Trampa:** `true === 'true'` es `false` en JS; inyecta desde Kotlin el **literal** `true`/`false`, no una cadena.

**Radio.** No está implementado en el origen (solo se lista en sondas). Constrúyelo con el patrón del checkbox: localizar el grupo por la etiqueta de la opción, `fullClick`, verificar `aria-checked`/`checked`.

**Listas de selección múltiple (grupos).** Carga perezosa con scroll (8 intentos × 4 scrolls de 400 px). Empareja por **F1 sobre palabras significativas** (sin tildes ni palabras vacías, prefijos ≥ 4 letras a medio punto), umbral 0,5; tolera emojis y sufijos como «N mil miembros»; verifica `aria-checked==='true'` tras cada clic.

**Botones de avance.** Localizar por texto/`aria-label` exacto (bilingüe), `fullClick`, y verificar que la pantalla cambió (URL, desaparición del botón o aparición de marcadores del siguiente paso).

### 4.4 Orden de llenado y formularios progresivos
1. Archivos primero si están arriba del formulario.
2. El **campo que despliega a los demás** (p. ej. «tipo») antes que el resto, y `delay(1200)` después.
3. Campos con **dependencias en cascada** (año → marca → modelo) en ese orden.
4. Texto libre y checkboxes al final.
5. Los campos con valor vacío en el CSV **se omiten** (nunca borrar un valor existente por un vacío).

### 4.5 Esperas y timeouts de referencia

| Mecanismo | Valor |
|---|---|
| Pausa tras cada paso | 600 ms |
| Espera tras el campo que despliega los demás | 1200 ms |
| Espera tras subir archivos | 1000 ms |
| Apertura de diálogo | 12 × 150 ms (15 × 180 ms si es con búsqueda) |
| Cierre de diálogo tras seleccionar | 10 × 150 ms + Escape/clic fuera |
| Typeahead: escritura | 120–130 ms por carácter |
| Estabilización de sugerencias | hasta 40 × 200 ms, 4 firmas idénticas |
| Timeout por paso | 15 s por defecto; 45 s listas; 150–180 s typeahead |
| `CHECK_READY` | cada 500 ms hasta 20 s, 2 lecturas OK |
| Entrega de archivos | 30 s (producción) |

### 4.6 Salvaguardas de toque
`elementFromPoint` contra botones de acción · descartar toque si el ancho cambió · `dpr` del JS · `scrollIntoView` + 350 ms antes de medir · un solo llenado a la vez (`fillActive`).

**Checklist cap. 4:** [ ] `runStep` con timeout · [ ] Helpers compartidos · [ ] Una receta por tipo · [ ] Éxito = valor releído · [ ] Diálogos siempre cerrados · [ ] Vacíos no se escriben · [ ] Orden por dependencias · [ ] Literales booleanos desde Kotlin.

---

## 5. Datos CSV y mapeos

### 5.1 Parser tolerante (RFC 4180, propio)
- Quitar **BOM**; respetar comillas dobles, comas y saltos de línea dentro de campos; ignorar `\r`.
- Primera fila = cabecera. Descartar filas sin identificador. **Una columna faltante no es error**: valores por omisión y un `report()` con `missingColumns` en el log.
- Pruebas JUnit de tolerancia (`CsvToleranceTest`): BOM, comillas, saltos de línea, columnas ausentes, filas vacías.

### 5.2 Campos embebidos
Los CSV exportados traen estructuras serializadas dentro de una celda. Extráelas con regex tolerantes, no con un parser JSON estricto (suelen usar comillas simples):
- Lista de URLs: `"[{'url':'https://…'},…]"` → `https?://[^\s'"}\]]+`.
- Objeto: `"{'city':'…','region':'…','postal_code':'…'}"` → regex por clave.
- Importe con moneda: `"1129000 MXN"` → dígitos para el monto, 3 letras para la moneda (con valor por omisión).
- Numéricos con formato: conservar solo dígitos.
- Listas simples: separador `|`.
- Booleanos: definir explícitamente qué valores son `false` (`false`, `0`, `no`) y que vacío/otro = `true`.

### 5.3 Normalización y mapas de valores
- `TextNorm`: minúsculas, sin tildes, espacios colapsados. Úsalo **siempre** al comparar (consulta, diff, mapeos).
- **Mapas origen→etiqueta del sitio** por campo (el CSV suele traer inglés/códigos y el sitio muestra español): `Red→Rojo`, `AUTOMATIC→"Transmisión automática"`. Cuidado con etiquetas **completas**, no abreviadas.
- Valores sin mapa → **fallback explícito** o fallo claro; nunca adivinar en silencio.
- Los mapas viven en un objeto `ValueMaps` testeado y se alimentan de lo observado en las sondas (`options_sample`).

### 5.4 Identificador de correspondencia
Si el flujo debe poder **relacionar el registro del sitio con la fila del CSV** después (actualizar, borrar, evitar duplicados), añade al final del texto libre un marcador estable (`\n\n---\n<PREFIJO>_<id>`). Recorta el texto base, **nunca el sufijo**, si hay límite de longitud. Valida antes de actualizar que el marcador existe.

### 5.5 Calidad de datos
- `id` repetido en el CSV: avisar en el log y **no** usar el id como clave de lista en Compose (la repetición cerró la app); usa clave por posición + id.
- Esquema de columnas **versionado** en el repo (`docs/csv-schema.md`) con: nombre, tipo, obligatoriedad, transformación, campo destino.
- Dos copias: la fuente editable (`data/input.csv`) y el asset empaquetado (`assets/inventory.csv`). Regla: al cambiar la primera, copiarla y recompilar.
- **Fuente en la nube:** HTTPS obligatorio, tope de tamaño (p. ej. 60 MB), respaldo local por URL (hash), solo guardar el respaldo si la descarga contiene registros; sin red, usar la copia.

**Checklist cap. 5:** [ ] Parser con pruebas · [ ] Embebidos con regex · [ ] `TextNorm` en todas las comparaciones · [ ] `ValueMaps` · [ ] Marcador de correspondencia · [ ] Esquema CSV documentado.

---

## 6. Flujos multipaso y multi-URL

El origen resolvió cada flujo con una función `suspend` escrita a mano. Para un sitio cualquiera hace falta un **modelo declarativo** que el `FlowRunner` interprete. Esta es la parte **por construir**; está diseñada a partir de los patrones ya validados.

### 6.1 Modelo `Flow`

```json
{
  "flow_id": "alta-demo",
  "start_url": "https://ejemplo.com/alta",
  "goal": { "screen": "confirmacion", "stop_before_final_action": true },
  "steps": [
    {
      "id": "paso-1",
      "detect": { "url_regex": "^https://ejemplo\\.com/alta", "text_regex": "Datos generales", "min_consecutive": 2 },
      "schema": "schemas/paso-1.json",
      "mapping": "mappings/paso-1.json",
      "pre": [ { "action": "dismiss", "labels": ["Cerrar","Close"] } ],
      "fill": "all_required_then_optional",
      "advance": { "click": ["Siguiente","Next"], "expect": { "url_regex": "step=2" }, "timeout_s": 20 },
      "on_fail": "stop_and_report"
    },
    {
      "id": "paso-2",
      "detect": { "url_regex": "step=2", "text_regex": "Revisar" },
      "schema": "schemas/paso-2.json",
      "mapping": "mappings/paso-2.json",
      "advance": { "click": ["Enviar","Submit"], "irreversible": true }
    }
  ]
}
```

### 6.2 Algoritmo del `FlowRunner`
```
abrir start_url ; esperar «pantalla lista» según detect del paso actual
repetir:
  paso = identificar_paso()          // por URL + texto; si ninguno coincide → STOP «pantalla desconocida» + volcar sonda
  si paso.pre → ejecutar
  FormFiller.fill(paso.schema, paso.mapping, fila_csv)   // cap. 4, verificando valores
  si algún campo requerido falló → STOP «campo_requerido_fallido» + reporte
  si paso.advance.irreversible → PEDIR confirmación humana (cap. 7) ; si no confirma → STOP
  hacer advance.click ; esperar advance.expect (URL/texto) hasta timeout
hasta alcanzar goal.screen
```

### 6.3 Detección de pantalla/estado
- **URL** (regex) **+ texto del DOM** (regex) **+ marcadores de control** (existe tal input). Exige 2 lecturas consecutivas.
- Maneja **recargas** y redirecciones intermedias: el sitio puede montar el DOM dos veces; no hagas nada hasta estabilizar.
- Maneja **interrupciones comunes**: banners/consejos que hay que cerrar, diálogos de cookies, pantalla de login (→ pedir al humano), captcha/aviso/bloqueo (→ detener, cap. 7).
- Cada paso que **avanza URL** tiene un `expect`; sin cambio verificable no se da por avanzado (hay sitios que recargan y destruyen el script antes de responder: confirma con un script mínimo `WHERE` que la ruta cambió).
- **Un paso desconocido siempre termina en STOP con una sonda automática** de la pantalla (para alimentar el siguiente `FormSchema`).

### 6.4 Modo descubrimiento multipaso
Para una URL nueva sin `Flow` todavía: ejecutar sondas en `start_url`, generar `FormSchema` (3.4), pedir mapeo aprobado, llenar **sin avanzar**, hacer `advance` solo con confirmación humana la primera vez, sondear la pantalla siguiente y **añadir un paso al `Flow`**. Repetir hasta el objetivo. El `Flow` resultante queda en el repo y se reutiliza sin humano en corridas posteriores.

### 6.5 Robustez
- Serializar con `fillActive`. Cada paso con timeout y un motivo de fallo tipificado (`etiqueta_no_encontrada`, `no_abrio_dropdown`, `opcion_no_encontrada`, `valor_no_aplicado`, `pantalla_desconocida`, `timeout`).
- **Reintentos:** solo para pasos idempotentes de lectura/navegación. **Nunca** reintentar automáticamente una acción irreversible.
- **Idempotencia del llenado:** relanzar el mismo CSV sobre la misma pantalla debe producir «sin cambios».

**Checklist cap. 6:** [ ] `Flow` JSON validado · [ ] `detect` con 2 lecturas · [ ] `expect` en cada avance · [ ] STOP + sonda ante pantalla desconocida · [ ] Sin reintento de irreversibles.

---

## 7. Seguridad, límites y ritmo

1. **Confirmación humana explícita** antes de cualquier acción irreversible (enviar, publicar, pagar, eliminar). El botón de acción final solo aparece/activa tras un llenado con todos los pasos OK y se confirma con un diálogo que resume lo que se va a enviar. No existe «modo automático» sin una decisión explícita y documentada del proyecto.
2. **Lista cerrada de campos modificables** (`ALLOWED_LABELS`) para operaciones de edición. Los campos identitarios/inmutables (ids, claves, vínculos) **nunca** se leen para escribir ni se cambian. Tras llenar y antes de guardar, **relee** y aborta si cambió un campo inmutable (`ImmutableGuard`).
3. **Marcador de correspondencia obligatorio** (5.4) antes de editar/borrar un registro: sin él no se opera.
4. **Verificación previa a borrar:** releer la ficha 2 veces, verificar que el diálogo menciona el título esperado, y abortar ante cualquier discrepancia. Fallar es seguro; borrar mal no.
5. **Ritmo humano:** pausas aleatorias entre operaciones (rangos documentados por tipo), horario laboral, topes diarios por dispositivo/cuenta, y escalonar operaciones reales en varios días (cuenta «fría»). Define los números **del sitio objetivo** en el capítulo 12.
6. **Parada ante señales del sitio:** captcha, aviso de actividad inusual, bloqueo temporal, verificación de identidad → detener el lote y reportar; no insistir.
7. **Modo prueba aislado:** solo en APK debug; whitelist de ids; presupuestos diarios; el modo prueba **nunca** puede ejecutar la acción final ni borrar (`UiRegistry.blocked`).
8. **Secretos:** nada de credenciales en el repo, en logs ni en informes; la sesión del WebView no se exporta. `auth/`, `*.keystore`, `.env` en `.gitignore`.
9. **Descargas:** HTTPS, tope de tamaño, tiempos de conexión/lectura (15 s / 20 s), redirecciones manuales con límite (5).
10. **Revisar términos de uso del sitio objetivo** antes de automatizar; es decisión del humano (cap. 12).

**Checklist cap. 7:** [ ] Confirmación humana de la acción final · [ ] `ALLOWED_LABELS` + `ImmutableGuard` · [ ] Pausas y topes configurables · [ ] Parada ante captcha/aviso · [ ] `.gitignore` de sesión/credenciales.

---

## 8. Automatización de pruebas

Objetivo: poder ejecutar y juzgar casos reales **sin tocar el teléfono**, con resultados reproducibles y evidencia.

### 8.1 Arquitectura del modo prueba

```
Mac (script bash/python)                          Teléfono (APK debug)
 run_case.sh ── adb shell am broadcast ─────────►  TestHarness (BroadcastReceiver)
   -a <pkg>.TEST --es cmd ... --es run <id>          │ ejecuta comando
                                                      ▼
 ◄── logcat -s <TAG> : TEST_EVENT {json} ─────────  emit(): línea en logcat
 ◄── adb shell run-as <pkg> cat files/test/out/<run>.json   (resultado completo, sin truncar)
```

- **Receptor solo en debug** (acción `<pkg>.TEST`, `RECEIVER_EXPORTED` desde Android 13). **Un solo receptor activo por proceso:** al registrar uno nuevo, retirar el de instancias viejas de la Activity; si no, cada comando llega N veces (incidente real con `am start` repetidos).
- **Extras** (`--es`): `cmd`, `vehicle`/`id`, `run` (id único), `file`, `n`, `files`, `value`, `url`… `am broadcast` **descarta extras vacíos**: manda `none`. Valores con espacios/comillas van entre comillas simples dentro de `adb shell "…"`.
- **Evento** `TEST_EVENT {json}` con `run`, `cmd`, `status`, `final`, más datos. Si es final, escribirlo también a `files/test/out/<run>.json` (logcat trunca ≈ 4 KB y `logcat -c` lo borra).
- **Comandos tipo:** `status`, `load_csv`, `source`, `plan_<x>` (solo lectura: calcula cambios), `apply_<x>` (escribe), `dry_fill` (llena y relee **sin enviar**), `stop`, `ui_list`/`ui` (ejecutar una acción de la barra por nombre, **bloqueando** las irreversibles), `probe_*` (todas las sondas), `guard_probe`, `survey_map`, `read_*`.
- **Entradas en el teléfono:** `files/test/<caso>.csv` y `files/test/whitelist.txt` vía `run-as <pkg> sh -c 'cat > files/test/<f>' < archivo_local`.

Esqueleto Kotlin: ver Anexo C.4.

### 8.2 Ejecutor `run_case.sh` (determinista, sin IA)
Pasos y por qué existen:
1. Copiar el CSV del caso al teléfono (`run-as`).
2. `KEYCODE_WAKEUP` (avisar si el fabricante lo bloquea).
3. `am start -f 0x24000000 -n <pkg>/.MainActivity` (**CLEAR_TOP|SINGLE_TOP**): sin estos flags se acumulan Activities, cada una con su receptor.
4. **Saludo previo:** `status` con run `<RUN>H` hasta 30 veces cada 2 s; esperar `"run":"<RUN>H"` en logcat. Con el teléfono dormido, el arranque tarda > 15 s y un comando enviado antes se pierde.
5. Si no responde → `force-stop`, relanzar, reintentar una vez; si sigue → `{"status":"app_no_responde"}` (exit 4).
6. `logcat -c`, `load_csv`, comando real.
7. Sondear cada 3 s `TEST_EVENT` con `"run":"<RUN>"` y `"final":true`; preferir el JSON completo de `files/test/out/<RUN>.json`.
8. Timeout → `{"status":"timeout"}` (exit 1).
9. **Precheck `EXPECT="EtiquetaA,EtiquetaB"`:** antes de un `apply_*`, correr `plan_*` (solo lectura) y **abortar** (`precheck_mismatch`, exit 3) si los cambios detectados no coinciden exactamente con lo esperado. Protege contra CSV mal armados antes de una escritura real.

Código: Anexo C.5.

### 8.3 El oráculo vive en la app (P7)
`status` ∈ `pass | fail | inconclusive | rejected | error | waiting | ok | timeout | app_no_responde`. Los dos últimos los pone el script; el resto, la app.

`apply_*` es **pass** solo si, tras guardar, espera ≈ 3 s, **relee** el formulario y cumple las tres condiciones:
1. `residual` vacío: los campos probados quedaron con el valor del CSV.
2. `collateral` vacío: ningún otro campo cambió respecto a la lectura previa.
3. Los textos largos son idénticos conservando saltos de línea (`descriptionExact != false`).

Equivale a **idempotencia**: aplicar el mismo CSV debe dar «sin cambios». Si no hay nada que cambiar → `pass` sin consumir presupuesto. Si cambian campos inmutables → `fail` y no guarda. Verificaciones por contenido (p. ej. orden de imágenes por hash perceptual dHash de 64 bits, distancia ≤ 8 = misma imagen) cuando el sitio cambia los identificadores al guardar → `inconclusive` si el conteo es correcto pero el orden no se puede verificar.

### 8.4 Datos de prueba: matrices acumulativas
- **Un registro banco** (id fijo, whitelisted) que se restaura a su estado real al final. Todo caso de escritura se aplica **solo** a ese id.
- Los CSV de escritura son **acumulativos**: cada uno parte del estado en que dejó el anterior y cambia lo **mínimo**; cada caso lleva su `EXPECT`. Una base mal armada (cambió 2 campos en vez de 1) fue el error de diseño más repetido.
- **Convención de prefijos por bloque:** `A` un campo por caso · `B` combinaciones · `C` solo lectura (idempotencia, vacíos, formatos, ceros, textos largos/especiales, valor inexistente, id fuera de whitelist) · `ER` errores (recurso 404, formatos inválidos) sin guardar · `PH` archivos con guardado · `NEG` controles negativos (deben dar `fail` sin guardar) · `MANUAL_*` · `DEL` bajas (irreversibles, solo con visto bueno) · `RESTAURAR_*`.
- Generadores (`make_matrix*.py`) producen CSV y `matrix.json` con los `EXPECT`; los CSV son artefactos reproducibles.
- **Presupuesto:** topes diarios de escrituras (p. ej. 10) y de operaciones costosas (p. ej. 5), con pausas aleatorias de 1–3 min **antes** de abrir el formulario (con el WebView en segundo plano un formulario abierto se queda sin responder). Prefiere `plan_*`, `dry_fill`, `read_*`.
- **Banco de archivos de prueba** versionado solo como `MANIFEST.csv` (los binarios en `.gitignore`), con originales, formatos, dimensiones, proporciones, pesos, EXIF y transparencias; cada archivo distinto para poder verificar por contenido.

### 8.5 Matrices transversales (para todo proyecto)
| Matriz | Qué valida | Herramienta |
|---|---|---|
| Datos (A/B/C) | Llenado por campo/combinación, idempotencia, vacíos, formatos | `run_matrix_*.py` + `run_case.sh` |
| Errores | Recurso inexistente (404), formato no admitido, peso excesivo; el estado queda intacto | `run_matrix_errors.py` |
| Orientación | Giro antes/durante; layout y toque | `rotate.sh` + `dry_fill` + sonda geométrica |
| Interrupciones | Pantalla apagada, app a segundo plano, `force-stop`, corte de WiFi (solo con USB) a los N s de iniciar | `run_matrix_interrupt.py` |
| Multi-dispositivo | ≥ 2 versiones de Android y ≥ 2 tamaños | cap. 9 |
| Catálogos/entradas | Sweep de todos los orígenes de datos (p. ej. los 6 catálogos) | `run_*_catalogs.py` |

`rotate.sh` (Anexo C.6) **verifica** el giro midiendo las dimensiones de `screencap` y sale con 3 si no giró: una versión anterior ignoraba en silencio el error de `cmd window user-rotation` en Android 10 y validó pruebas que nunca rotaron. Al terminar, **siempre** devolver la rotación automática (`rotate.sh <dev> auto`).

### 8.6 Informes y trazabilidad
- Informe por lote: `docs/qa/AAAA-MM-DD-<lote>.md` con cabecera (dispositivo, id, fecha), tabla `caso | status | cambios | residual | collateral`, JSON crudo, incidentes, observaciones (captcha, avisos).
- Evidencia cruda: `docs/qa/AAAA-MM-DD[-vXXX]-<tema>[-<dispositivo>][-FALLO|-FINAL|-INFRA].json`.
- **Inventario maestro** `docs/qa/INVENTARIO-PRUEBAS.md`: tabla prueba × dispositivo × resultado × evidencia. Leyenda ✅ ⚠️ ❌ 🔧 ⏳ ➖ 🔶. **Un ❌ nunca se borra**: se marca 🔧 con una nota breve cuando se corrige. Al cerrar una prueba se actualiza su fila y se enlaza el informe.

### 8.7 Agentes de QA (roles, ver Anexo A)
`qa-planner` (Sonnet) diseña matrices → el humano aprueba → `qa-runner` (Haiku) ejecuta el lote aprobado y **no decide pass/fail** → `qa-analyst` (Sonnet) clasifica cada no-pass con **evidencia literal** → el orquestador (modelo más capaz) y el humano deciden y corrigen. Principio: *el modelo capaz decide; los baratos ejecutan.* Lección: un agente nuevo no se carga hasta reiniciar la sesión.

**Checklist cap. 8:** [ ] Receptor único solo en debug · [ ] `TEST_EVENT` + archivo de salida · [ ] `run_case.sh` con saludo, reintento y `EXPECT` · [ ] Oráculo en la app · [ ] CSV acumulativos con `EXPECT` · [ ] Whitelist + presupuestos · [ ] Matrices de errores/orientación/interrupciones · [ ] Informes + inventario maestro.

---

## 9. Infraestructura adb y dispositivos

### 9.1 Conexión
- **Con cable:** `adb tcpip 5555` → `adb connect <IP>:5555`. **Sin cable:** `adb connect` mientras el modo tcpip siga activo (un reinicio del teléfono lo pierde). **Android 11+:** `adb pair <IP>:<puerto_emparejamiento>` + código de 6 dígitos.
- Descubrir IP: `adb shell ip route | grep src` (con cable) o `nmap -p 5555 --open <subred>`.
- **Regla:** con varios dispositivos, **siempre** `adb -s <IP>:5555` (o serial). Registra los equipos en `docs/guia-conexion-adb-wifi.md` y, si hay producción, en un registro de dispositivos.

### 9.2 Problemas conocidos
| Síntoma | Causa / solución |
|---|---|
| `device offline` | `adb disconnect` + `connect` |
| «No route to host» aunque haya ping | `adb kill-server` |
| Daemon colgado | `pkill -9 adb` |
| `INSTALL_FAILED_USER_RESTRICTED` (MIUI/Xiaomi) | Activar «Instalar vía USB»/«Depuración USB (ajustes de seguridad)»; primera instalación manual si hace falta (`push` a `/sdcard/Download` e instalar desde Archivos); aceptar el diálogo en pantalla o `install -r` se cuelga en «Performing Streamed Install» |
| Xiaomi bloquea `input keyevent`/`settings put global` | Falta «Depuración USB (ajustes de seguridad)»: no se puede despertar por adb → dejar la pantalla encendida a mano |
| Pantalla apagada → WebView sin respuesta | La app debug mantiene la pantalla encendida; el saludo `status` evita perder comandos |
| Tableta con bloqueo seguro tras `KEYCODE_SLEEP` | adb no puede desbloquear → invalida la corrida (marcar `-INFRA`) |
| Puerto de depuración inalámbrica cambia al bloquear | Redescubrir el puerto |
| `adb pair` falla | Consume la ventana de emparejamiento: pedir código nuevo |
| Apagar WiFi corta adb inalámbrico | Usar USB para esas pruebas |
| `python3 -I` no ve PIL | No usar `-I` en scripts con PIL |
| `am broadcast` pierde extras vacíos | Mandar `none` |
| La app deja de responder a broadcasts tras una matriz larga (no reproducida) | `force-stop` + relanzar (ya en `run_case.sh`); si reaparece, guardar `logcat -v time` completo y buscar un ANR **antes** de reiniciar |

### 9.3 Estrategia de dispositivos
- Cubrir **≥ 2 versiones de Android** y **≥ 2 tamaños** (teléfono + tableta). Los fallos de layout no aparecen en uno solo.
- Un equipo para pruebas largas con USB y otro, personal, **solo para validaciones cortas**.
- Los resultados se anotan por dispositivo en el inventario maestro.

---

## 10. Catálogo de lecciones y antipatrones

| # | Síntoma | Causa | Solución / regla |
|---|---|---|---|
| L1 | El formulario no monta; «no hay input[type=file]» | `pauseTimers()` global | Solo `onPause`/`onResume` por instancia; `onPause` solo sin llenado activo |
| L2 | Dropdown reportado como fallo aunque se aplicó | Éxito medido por «cerró el diálogo»; un diálogo residual de otro widget daba falso negativo | Verificar el valor releído de la fila (P4) |
| L3 | «Opción no encontrada» en pantallas anchas | Un panel de la barra del sitio (`role=dialog`) se tomó por el dropdown | `isChromePanel` + `findOpenDialog` filtran por contenido |
| L4 | Un checkbox nunca cambia | `true==='true'` es `false` | Inyectar el literal booleano |
| L5 | El valor escrito se pierde | Inputs controlados por React | Setter nativo + `InputEvent` carácter a carácter |
| L6 | El toque nativo cae en otro sitio en un equipo | `displayMetrics.density` ≠ `devicePixelRatio` del WebView | Usar el `dpr` que reporta el JS |
| L7 | El selector de archivos no abre | `input.click()` sin *user activation* | JS localiza, Kotlin toca (nativo) |
| L8 | El toque cae fuera del viewport | Input fuera de pantalla | `scrollIntoView` + 350 ms antes de medir |
| L9 | El toque cae sobre el botón «Guardar» | Contenedor enorme / layout distinto al girar | `elementFromPoint` + lista de botones de acción + salvaguarda de giro |
| L10 | Cada comando de prueba llega N veces | N Activities, cada una con su receptor | Receptor único por proceso + `am start -f 0x24000000` |
| L11 | Pantalla en blanco | UA móvil, o reescritura de URLs de login | UA de escritorio; `shouldOverrideUrlLoading=false` |
| L12 | La Activity se recrea al girar | Falta `screenLayout` en `configChanges` | Manifiesto completo (2.7.1) |
| L13 | Formulario con alto 0 en horizontal/tableta | Layout de dos columnas por ancho CSS | WebView ≤ 720 dp + sonda geométrica; probar tableta |
| L14 | Una imagen de respaldo reemplazó datos reales | Descarga parcial «tolerante» | Descargas estrictas: todo o nada |
| L15 | App cierra con CSV | `id` repetido como clave de `LazyColumn` | Clave por posición + id; aviso en log |
| L16 | La prueba «pasó» pero nunca giró la pantalla | Script ignoraba el error del comando de rotación | Verificar con `screencap`; exit 3 si no giró |
| L17 | Diagnóstico de un agente era falso («condición de carrera») | Aceptarlo sin contrastar con el log | Evidencia literal obligatoria (P1) |
| L18 | CSV de caso cambia 2 campos en vez de 1 | Base no partía del estado real | CSV acumulativos + `EXPECT` + precheck |
| L19 | Resultados perdidos | `logcat -c` y truncado a 4 KB | Archivo `out/<run>.json` |
| L20 | Se pierde el comando por pantalla dormida | Arranque lento | Saludo `status` previo |
| L21 | Éxito parcial al guardar se pierde | El sitio recarga y destruye el script antes de responder | Confirmar con script mínimo que la ruta cambió |
| L22 | Datos mal mapeados (inglés vs español) | Mapas incompletos / etiquetas abreviadas | `ValueMaps` completos con etiquetas reales observadas |
| L23 | Un campo vacío del CSV borraba un valor | Vacío tratado como dato | Vacío = no tocar |
| L24 | Diferencias solo de saltos de línea | Normalización del diff | Normalizar en el diff; oráculo estricto aparte |
| L25 | El paso falla por «no se encontró la etiqueta» tras cambiar el idioma | Etiquetas solo en un idioma | Listas bilingües |
| L26 | Documentación de selectores quedó obsoleta | Basada en clases CSS | Mantener solo etiquetas y comportamientos; marcar docs obsoletos |
| L27 | Errores que solo aparecían en otro dispositivo | Validar en un único equipo | P8 |
| L28 | Fotos no se verificaban por id | El sitio cambia los ids al guardar | Verificar por contenido (hash perceptual) |

**Reglas de oro del agente:** verificar el log antes de aceptar un diagnóstico · para ejecutar, no concluir · ante señal del sitio (captcha/aviso/bloqueo), detenerse · no compilar ni instalar desde el agente `runner` · no tocar `~/.claude` desde agentes de lote.

---

## 11. Plan de arranque del nuevo proyecto

| Fase | Entregable | Criterio de salida |
|---|---|---|
| **F0** Esqueleto | App Compose + WebView configurado (cap. 2) + login manual + log a logcat con `<TAG>` + versionado | Carga la URL inicial, sesión persiste tras reiniciar, log visible por adb |
| **F1** Sondas y esquema | Menú Sondas (3.2) + `FormSchema` por pantalla + «pantalla lista» | Un `FormSchema` correcto de la pantalla 1, validado a mano contra la página |
| **F2** Llenado | `FormFiller` + helpers + recetas (4.3) + CSV/`ValueMaps` (cap. 5) + mapeo aprobado | `dry_fill` de un registro: todos los campos con valor releído correcto |
| **F3** Multipaso | `FlowRunner` + modelo `Flow` (cap. 6) + modo descubrimiento | Llega a la pantalla objetivo y se detiene antes de la acción final; confirmación humana funciona |
| **F4** Harness QA | `TestHarness` + `run_case.sh` + oráculo + whitelist/presupuestos (cap. 8) | Un `plan_*` y un `apply_*` reproducibles con `pass` verificado en el log |
| **F5** Matrices y dispositivos | Matrices A/B/C/ER/orientación/interrupciones + inventario maestro + ≥ 2 dispositivos | Todo en ✅ o con ❌/🔧 documentado, en ≥ 2 versiones de Android |

Reglas de proceso: una fase por sesión de trabajo como máximo · al cerrar cada fase actualizar `docs/plan/estado-y-siguientes-pasos.md` y `memory.md` · no hacer commit ni push sin que el humano lo pida.

---

## 12. Datos que debe aportar el humano en el nuevo proyecto (preguntas abiertas)

La IA **debe preguntar esto antes de F0** y registrar las respuestas en `CLAUDE.md`:

**Objetivo y sitio**
1. ¿Cuál es el sitio y la URL inicial? ¿Cuál es el **objetivo final** (qué pantalla/estado se considera «hecho»)?
2. ¿Cuál es la **acción final irreversible** y quién la confirma? ¿Se permite alguna vez automatizarla?
3. ¿El sitio permite automatización según sus términos? ¿Quién asume esa decisión?
4. ¿Hay API oficial que sustituya al WebView? (Si existe, es más robusta; documentar por qué se descarta.)

**Acceso**
5. ¿Hay login? ¿2FA? ¿Captcha? ¿La sesión caduca cada cuánto? ¿Hay varias cuentas?
6. ¿Idiomas/regiones de las cuentas (afecta a las etiquetas bilingües)?

**Datos**
7. ¿Origen y esquema del CSV (columnas, obligatorias, transformaciones)? ¿Local o en la nube? ¿Volumen por lote?
8. ¿Cuál es la **clave de correspondencia** fila↔registro del sitio? ¿Se puede añadir un marcador al texto libre?
9. ¿Hay archivos adjuntos (imágenes, documentos)? ¿Formatos, pesos, cantidad máxima, hospedaje de origen?
10. ¿Valores del CSV que no coinciden con las etiquetas del sitio (mapas necesarios)?

**Operación**
11. ¿Solo **crear**, o también **actualizar** y **borrar**? ¿Qué campos son inmutables?
12. ¿Ritmo permitido: topes diarios, pausas, horario? ¿Qué señales del sitio obligan a detener?
13. ¿Quién usará la app (usuario final vs. equipo técnico)? ¿Distribución (APK directo, Play)? ¿Se ocultan menús de prueba en release?

**Pruebas**
14. ¿Existe un **registro banco** que se pueda modificar libremente? ¿O un entorno de pruebas/sandbox del sitio?
15. ¿Qué dispositivos hay (modelos, Android, tamaños)? ¿Cuál es personal y cuál de pruebas?
16. ¿Tope de escrituras reales por día en pruebas?
17. ¿Qué modelo/agentes se usarán para planificar, ejecutar y analizar (cap. 8.7)?

**Estado esperado de salida:** sección «Decisiones del proyecto» en `CLAUDE.md` con las 17 respuestas.

---

## Anexo A. Plantillas de agentes de QA (`.claude/agents/`)

Ajusta `<...>`. Los agentes nuevos no se cargan hasta reiniciar la sesión de Claude Code.

**`qa-planner.md`**
```markdown
---
name: qa-planner
description: Diseña la matriz de casos de prueba (CSV acumulativos + cambios esperados) para el modo prueba de <ACCIÓN>. Lee el código y escribe SOLO en data/test/ y scripts/qa/. No toca el teléfono.
model: sonnet
tools: Read, Grep, Glob, Write, Edit
---
Eres el diseñador de casos QA de <PROYECTO>. Produces archivos; no ejecutas nada.

Reglas:
- Lee primero el plan de QA del proyecto, `data/test/README.md`, el código del diff/plan (<ARCHIVO>) y `scripts/qa/make_matrix*.py`. Los campos inmutables (<LISTA>) nunca se prueban.
- Los casos de escritura son ACUMULATIVOS: cada CSV parte del estado en que dejó el anterior y cambia lo mínimo. Cada caso lleva su lista EXPECT (etiquetas exactas que `plan_*` debe detectar).
- Presupuesto: máximo <N> escrituras al día; propón el calendario por día. Prefiere casos de SOLO LECTURA cuando basten.
- Todo caso nuevo cambia solo el registro banco `<ID_BANCO>`. Nunca diseñes casos que ejecuten la acción final ni borren.
- Escribe únicamente en `data/test/` y `scripts/qa/`. Entrega: lista de casos con valor probado, EXPECT, escrituras que consume y el riesgo que cubre.
```

**`qa-runner.md`**
```markdown
---
name: qa-runner
description: Ejecuta un lote APROBADO de casos con scripts/qa/run_case.sh y reporta el veredicto del oráculo. Usar solo cuando el humano ya aprobó el lote.
model: haiku
tools: Bash, Read, Write
---
Eres el ejecutor de pruebas QA de <PROYECTO>. Trabajo mecánico: corres casos y reportas. NO decides si algo «pasó»: lo decide el campo `status` del JSON que devuelve el script (oráculo de la app).

Reglas (innegociables):
- Solo puedes ejecutar `scripts/qa/run_case.sh`, `scripts/qa/push_whitelist.sh` y `adb -s <IP>:5555 logcat -d -s <TAG>`. Nada más en Bash: no compiles, no instales, no edites código, no toques `~/.claude`, no uses curl.
- Solo los casos del lote que te pasaron, en el orden dado. Un caso por invocación, sin reintentos automáticos de `apply_*` (cada uno es una escritura real).
- Comando: `scripts/qa/run_case.sh <IP>:5555 apply_update <ID_BANCO> <caso>.csv 420`.
- Si un resultado es `fail`, `inconclusive`, `rejected`, `timeout` o aparece captcha/aviso/bloqueo del sitio: DETENTE y reporta; no sigas con el resto del lote.
- Presupuesto: máximo <N> escrituras al día; la app ya lo impone y hace las pausas.
- Nunca ejecutes la acción final ni borres.

Informe (breve, en español): tabla caso | status | cambios | residual | collateral, y el JSON crudo de cada caso. Guárdalo en `docs/qa/AAAA-MM-DD-<lote>.md` si el lote lo pide. Si algo es ambiguo, repórtalo sin interpretarlo.
```

**`qa-analyst.md`**
```markdown
---
name: qa-analyst
description: Lee los informes de docs/qa/ y el log <TAG> de un lote, clasifica cada resultado no-pass (bug de la app, dato de prueba, cambio del sitio, infraestructura/adb) con EVIDENCIA del log y propone la corrección. Solo lectura.
model: sonnet
tools: Read, Grep, Glob, Bash
---
Eres el analista QA de <PROYECTO>. NO ejecutas casos ni aplicas correcciones: lees y clasificas.

Reglas (innegociables):
- Cada clasificación DEBE citar evidencia literal (línea del log `<TAG>`, JSON `TEST_EVENT`, fragmento de código con ruta:línea). Sin evidencia escribe «no concluyente» y di qué dato falta. Nunca aceptes el diagnóstico de otro agente sin contrastarlo con el log.
- Bash solo para lectura: `adb -s <IP>:5555 logcat -d -s <TAG>`, `grep`, `cat`, `git log/diff`. Prohibido: instalar, compilar, `am broadcast`, `run_case.sh`, editar archivos, `curl`, tocar `~/.claude`.
- Antes de culpar a la app descarta: dato de prueba mal armado (¿el CSV parte del estado real? ¿EXPECT correcto?), comandos duplicados/instancias de Activity, pantalla apagada, adb desconectado.
- Clasificación (una por resultado): `bug-app` · `dato-de-prueba` · `cambio-del-sitio` · `infraestructura` · `no-concluyente`. Añade severidad (alta si es destructivo para un registro real) y la corrección propuesta (archivo y cambio mínimo), SIN aplicarla.
- Casos ambiguos o de severidad alta: marca «escalar al orquestador».

Entrega: tabla caso | clasificación | evidencia | corrección propuesta | severidad.
```

---

## Anexo B. `CLAUDE.md` / `AGENTS.md` iniciales (copiar y ajustar)

```markdown
# CLAUDE.md — Guía para Claude

Lee este archivo antes de trabajar. Contexto completo en `AGENTS.md`, `memory.md` y `docs/plan/estado-y-siguientes-pasos.md`.

## Proyecto y alcance
App Android nativa (Kotlin + Jetpack Compose) en `android-app/` que, dada una URL inicial y un CSV, explora el formulario con sondas DOM, lo llena y sigue los pasos hasta el objetivo `<OBJETIVO>`. Sitio objetivo: `<SITIO>`. Guía base: `docs/plan/guia-reutilizable-automatizacion-formularios-webview.md`.

## Decisiones y límites obligatorios
- Kotlin nativo; sin migrar a Expo/Flutter/iOS sin decisión explícita.
- Nunca ejecutes la acción final irreversible sin confirmación humana explícita tras completar el formulario.
- Subida de archivos «cero toques»: JS localiza + `scrollIntoView`; Kotlin simula el toque nativo; el `WebChromeClient` entrega los archivos. `input.click()` por JS no sirve.
- UA del WebView configurable (por omisión de escritorio).
- Localiza controles por TEXTO de etiqueta, nunca por clases CSS.
- No uses `pauseTimers()` global; usa `onPause`/`onResume` por instancia.
- Conserva el marcador `<PREFIJO>_<id>` al final del texto libre.
- Respeta los límites de ritmo: `<TOPES, PAUSAS, HORARIO>`. Detente ante captcha/aviso/bloqueo.
- No leas, incluyas en cambios ni publiques credenciales ni la sesión del navegador (`auth/`, `.env` en `.gitignore`).

## Flujo de trabajo
1. Lee completo el archivo relevante antes de modificarlo (DOM y orquestador son sensibles).
2. Diagnostica con evidencia (logcat `<TAG>` y sondas DOM). No afirmes que funciona sin el log.
3. Si se instala un build, incrementa `versionCode` y `versionName` en `android-app/app/build.gradle.kts`.
4. Compila desde `android-app/` con JDK 17: `export JAVA_HOME=<ruta_jdk17>; ./gradlew assembleDebug`.
5. Pruebas reales: el humano actúa en el dispositivo y tú verificas `<TAG>` por adb con `-s <IP>:5555`.
6. Si cambia `data/input.csv`: `cp data/input.csv android-app/app/src/main/assets/inventory.csv` y recompila.
7. Actualiza `docs/plan/estado-y-siguientes-pasos.md` y `memory.md` cuando cambie el estado o las decisiones.
8. No hagas commit ni push salvo petición. Mensajes de commit descriptivos en español.

## Referencias
`AGENTS.md` · `README.md` · `memory.md` · `docs/plan/estado-y-siguientes-pasos.md` · `docs/guia-conexion-adb-wifi.md` · `docs/qa/INVENTARIO-PRUEBAS.md`
```

`AGENTS.md` debe añadir: mapa del código (tabla del 1.2), comandos adb frecuentes (`logcat -c`, `logcat -d -s <TAG>`, `install -r`, `am start -f 0x24000000`), dispositivos registrados y reglas de oro del capítulo 10.

---

## Anexo C. Fragmentos de código

### C.1 `configureWebView` (resumen)
Ver 2.1, 2.4 y 2.6. Estructura: `settings` → `CookieManager` → `webViewClient` (`shouldOverrideUrlLoading=false`, `onPageFinished`, `onReceivedError` solo main frame) → `webChromeClient = FileChooserClient(...)` → `addJavascriptInterface(..., "AppBridge")` → construir `FormFiller`/`FlowRunner`.

### C.2 `runStep` y `FormFiller`
Ver 4.1. Orquestación: `scope.launch { for (campo in orden) { ok = runStep(campo.label, FillScripts.<receta>(labels, valor), timeout); if (!ok && campo.required) { stop } } }`. Los valores se inyectan con `JSONObject.quote(valor)` (cadena JS segura) y las etiquetas con `labels.joinToString(prefix="[", postfix="]") { JSONObject.quote(it) }`.

### C.3 Toque nativo y conversión de coordenadas
```kotlin
private fun handleFileLocate(wv: WebView, data: String) {
    val json = JSONObject(data)
    if (json.optString("type") != "FILE_LOCATE") return
    if (!json.optBoolean("ok")) { locateFailed?.invoke(); return }
    val dpr = json.optDouble("dpr", 0.0)
    val scale = if (dpr > 0.0) dpr else resources.displayMetrics.density.toDouble()
    val vw = json.optDouble("vw", -1.0)
    if (vw > 0 && Math.abs(vw * scale - wv.width) > 4) { locateFailed?.invoke(); return }   // cambió el ancho
    val px = (json.getDouble("x") * scale).toFloat()
    val py = (json.getDouble("y") * scale).toFloat()
    val w0 = wv.width
    wv.post { if (wv.width != w0) locateFailed?.invoke() else simulateTap(wv, px, py) }
}

private fun simulateTap(wv: WebView, x: Float, y: Float) {
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
    wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up)
    down.recycle(); up.recycle()
}
```

JS `LOCATE_FILE_INPUT` (esqueleto):
```js
(function(){
  function send(p){ try{ window.AppBridge.postMessage(JSON.stringify(p)); }catch(e){} }
  var ACTION = /^(enviar|guardar|siguiente|publicar|eliminar|borrar|cancelar|submit|save|next|delete)\b/i;
  var inputs=[].slice.call(document.querySelectorAll('input[type="file"]'));
  var inp = inputs.find(function(i){ var a=(i.getAttribute('accept')||'').toLowerCase(); return a===''||a.indexOf('image')!==-1; }) || inputs[0];
  if(!inp){ send({type:'FILE_LOCATE',ok:false,message:'no hay input[type=file]'}); return; }
  var el=inp; for (var u=0;u<6;u++){ var r=el.getBoundingClientRect(); if(r.width>20&&r.height>20) break; if(!el.parentElement) break; el=el.parentElement; }
  el.scrollIntoView({block:'center'});
  setTimeout(function(){
    var r=el.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
    var hit=document.elementFromPoint(x,y);
    var txt=((hit&&(hit.innerText||hit.getAttribute('aria-label')))||'').trim();
    if(hit && ACTION.test(txt)){ send({type:'FILE_LOCATE',ok:false,message:'el punto cae sobre un botón de acción: '+txt}); return; }
    send({type:'FILE_LOCATE',ok:true,x:x,y:y,w:r.width,h:r.height,vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio});
  }, 350);
  true;
})();
```

### C.4 `TestHarness` (esqueleto)
```kotlin
class TestHarness(private val host: TestHost) {
    companion object { const val ACTION = "<pkg>.TEST" }
    fun handle(intent: Intent) {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val run = intent.getStringExtra("run").orEmpty()
        val id  = intent.getStringExtra("vehicle").orEmpty()          // «id» del registro; 'none' si no aplica
        host.scope.launch {
            try {
                when (cmd) {
                    "status"      -> emit(run, cmd, "ok", final = true)
                    "load_csv"    -> loadCsv(run, intent.getStringExtra("file").orEmpty())
                    "plan_update" -> update(run, cmd, id, apply = false)
                    "apply_update"-> update(run, cmd, id, apply = true)
                    "dry_fill"    -> update(run, cmd, id, apply = true, dry = true)
                    "stop"        -> { stopRequested = true; emit(run, cmd, "ok", final = true) }
                    else          -> emit(run, cmd, "error", final = true) { put("message", "comando desconocido") }
                }
            } catch (e: Exception) { emit(run, cmd, "error", final = true) { put("message", "excepción: ${e.message}") } }
        }
    }
    private fun emit(run: String, cmd: String, status: String, final: Boolean, extra: (JSONObject.() -> Unit)? = null) {
        val o = JSONObject().put("run", run).put("cmd", cmd).put("status", status).put("final", final)
        extra?.invoke(o)
        if (final && run.isNotEmpty()) try { File(File(dir, "out").apply { mkdirs() }, "$run.json").writeText(o.toString()) } catch (_: Exception) {}
        Log.d("<TAG>", "TEST_EVENT $o")
    }
}
```
Registro (solo debug) con un único receptor activo por proceso:
```kotlin
private companion object { var activeTestReceiver: Pair<MainActivity, BroadcastReceiver>? = null }
private fun registerTestReceiver() {
    val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { harness.handle(i) } }
    activeTestReceiver?.let { (act, old) -> try { act.unregisterReceiver(old) } catch (_: Exception) {} }
    activeTestReceiver = this to receiver
    val filter = IntentFilter(TestHarness.ACTION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    else registerReceiver(receiver, filter)
    testReceiver = receiver
}
// onDestroy: unregisterReceiver(testReceiver) y if (activeTestReceiver?.first === this) activeTestReceiver = null
```

### C.5 `run_case.sh` simplificado
```bash
#!/usr/bin/env bash
# Uso: scripts/qa/run_case.sh <IP:5555> <comando> [id] [csv_en_data/test] [timeout_s=420] [EXPECT="A,B"]
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
DEV="${1:?falta <IP:5555>}"; CMD="${2:?falta comando}"; ID="${3:-}"; CSV="${4:-}"; TMO="${5:-420}"; EXPECT="${6:-}"
PKG=<pkg>; TAG=<TAG>; A=("$ADB" -s "$DEV")

# Precheck: protege la escritura real
if [[ "$CMD" == apply_update && -n "$EXPECT" ]]; then
  PRE="$("$0" "$DEV" plan_update "$ID" "$CSV" 240)"
  GOT="$(python3 -c 'import json,sys;d=json.loads(sys.argv[1]);print(",".join(sorted(c["label"] for c in d.get("changes",[]))) if d.get("status")=="ok" else "ERR:"+d.get("status",""))' "$PRE")"
  WANT="$(python3 -c 'import sys;print(",".join(sorted(sys.argv[1].split(","))))' "$EXPECT")"
  [[ "$GOT" == "$WANT" ]] || { echo "{\"status\":\"precheck_mismatch\",\"expected\":\"$WANT\",\"got\":\"$GOT\",\"final\":true}"; exit 3; }
  CSV=""
fi
RUN="r$(date +%s)$RANDOM"
[[ -n "$CSV" ]] && "${A[@]}" shell "run-as $PKG sh -c 'mkdir -p files/test && cat > files/test/$CSV'" < "data/test/$CSV"

"${A[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || echo "aviso: no se pudo despertar la pantalla" >&2
"${A[@]}" shell am start -f 0x24000000 -n $PKG/.MainActivity >/dev/null

# Saludo previo con reintento (force-stop + relanzar una vez)
READY=0
for ATTEMPT in 1 2; do
  for _ in $(seq 1 30); do
    "${A[@]}" shell am broadcast -a $PKG.TEST --es cmd status --es vehicle none --es run "${RUN}H" >/dev/null 2>&1 || true
    sleep 2
    "${A[@]}" logcat -d -s $TAG | grep -q "\"run\":\"${RUN}H\"" && { READY=1; break; }
  done
  [[ $READY -eq 1 ]] && break
  [[ $ATTEMPT -eq 1 ]] && { "${A[@]}" shell am force-stop $PKG; sleep 2; "${A[@]}" shell am start -f 0x24000000 -n $PKG/.MainActivity >/dev/null; }
done
[[ $READY -eq 1 ]] || { echo "{\"run\":\"$RUN\",\"status\":\"app_no_responde\",\"final\":true}"; exit 4; }

"${A[@]}" logcat -c
[[ -n "$CSV" ]] && { "${A[@]}" shell am broadcast -a $PKG.TEST --es cmd load_csv --es file "$CSV" --es run "${RUN}L" >/dev/null; sleep 2; }
"${A[@]}" shell am broadcast -a $PKG.TEST --es cmd "$CMD" --es vehicle "${ID:-none}" --es run "$RUN" >/dev/null

END=$((SECONDS + TMO))
while (( SECONDS < END )); do
  LINE="$("${A[@]}" logcat -d -s $TAG | grep TEST_EVENT | grep "\"run\":\"$RUN\"" | grep '"final":true' | tail -1 || true)"
  if [[ -n "$LINE" ]]; then
    FULL="$("${A[@]}" shell "run-as $PKG cat files/test/out/$RUN.json" 2>/dev/null || true)"
    [[ "$FULL" == \{* ]] && echo "$FULL" || echo "${LINE#*TEST_EVENT }"
    exit 0
  fi
  sleep 3
done
echo "{\"run\":\"$RUN\",\"status\":\"timeout\",\"final\":true}"; exit 1
```

### C.6 `rotate.sh` (con verificación)
```bash
#!/usr/bin/env bash
# Uso: rotate.sh <IP:5555|serial> <v|h|hi|vi|auto> [retraso_s]   · SIEMPRE terminar con: rotate.sh <dev> auto
set -uo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
DEV="${1:?}"; MODE="${2:?}"; DELAY="${3:-0}"; (( DELAY > 0 )) && sleep "$DELAY"
A=("$ADB" -s "$DEV")
set_rotation() {   # método 1: Android 11+ ; método 2: Android 10
  local out; out="$("${A[@]}" shell cmd window user-rotation lock "$1" 2>&1)"
  if [[ "$out" == *"Unknown command"* || "$out" == *"rror"* || "$out" == *"xception"* ]]; then
    "${A[@]}" shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
    "${A[@]}" shell settings put system user_rotation "$1" >/dev/null 2>&1
  fi
}
dims() { "${A[@]}" exec-out screencap -p | python3 -c "import sys,struct;d=sys.stdin.buffer.read();print(*struct.unpack('>II',d[16:24]))"; }
case "$MODE" in
  v) set_rotation 0; WANT=portrait ;; h) set_rotation 1; WANT=landscape ;;
  vi) set_rotation 2; WANT=portrait ;; hi) set_rotation 3; WANT=landscape ;;
  auto) out="$("${A[@]}" shell cmd window user-rotation free 2>&1)"
        [[ "$out" == *"Unknown command"* || "$out" == *"rror"* ]] && "${A[@]}" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
        echo "rotación automática activada"; exit 0 ;;
  *) echo "modo desconocido: $MODE" >&2; exit 2 ;;
esac
sleep 2
read -r W H <<< "$(dims)"; GOT=portrait; (( W > H )) && GOT=landscape
echo "rotación $MODE → ($W, $H) → $GOT"
[[ "$GOT" == "$WANT" ]] || { echo "ERROR: la pantalla NO giró (esperaba $WANT)" >&2; exit 3; }
```

### C.7 Comandos adb de uso diario
```bash
adb -s <IP>:5555 logcat -c                                   # limpiar antes de cada prueba
adb -s <IP>:5555 logcat -d -s <TAG>                          # leer evidencia
adb -s <IP>:5555 install -r android-app/app/build/outputs/apk/debug/app-debug.apk
adb -s <IP>:5555 shell am start -f 0x24000000 -n <pkg>/.MainActivity
adb -s <IP>:5555 shell am broadcast -a <pkg>.TEST --es cmd status --es vehicle none --es run r1
adb -s <IP>:5555 shell "run-as <pkg> cat files/test/out/r1.json"
```

---

## Anexo D. Glosario y mapa del repo origen (consulta opcional)

**Glosario**
- **Sonda:** script JS de solo lectura que vuelca la estructura del DOM.
- **FormSchema:** descripción estructurada de los campos de una pantalla (cap. 3.4).
- **Flow:** descripción declarativa de pantallas, llenado y transiciones (cap. 6).
- **Oráculo:** lógica que decide pass/fail; vive en la app (cap. 8.3).
- **EXPECT:** lista exacta de etiquetas que un `plan_*` debe detectar antes de permitir un `apply_*`.
- **Residual / collateral:** campos que no quedaron con el valor pedido / campos que cambiaron sin pedirlo.
- **Banco:** registro de prueba único que se puede modificar y restaurar.
- **Cero toques:** la subida de archivos no requiere que el humano toque la pantalla.
- **`fillActive`:** candado que serializa llenados y mantiene el trabajo en segundo plano.

**Mapa del repositorio origen** (`android-app/app/src/main/java/com/fbautomanager/app/`, solo como referencia de diseño)

| Tema | Archivos |
|---|---|
| Activity, WebView, puente, ciclo de vida | `MainActivity.kt` |
| Orquestación y recetas JS | `FormFiller.kt`, `FormFillScripts.kt` |
| Sondas y utilidades de pantalla | `InjectionScripts.kt`, `PhotoHash.kt`, `PublishedSync.kt` |
| Selector de archivos | `PhotoInjectingWebChromeClient.kt`, `ImageSize.kt`, `PhotoSimilarity.kt` |
| Datos | `CsvInventory.kt`, `Vehicle.kt`, `InventorySource.kt`, `SalesSurvey.kt` |
| Typeahead/ubicación | `LocationProbe.kt`, `LocationGeo.kt` |
| Modo prueba y servicio | `TestHarness.kt`, `FillForegroundService.kt` |
| Pruebas automatizadas | `scripts/qa/run_case.sh`, `run_photo_case.sh`, `rotate.sh`, `run_matrix_*.py`, `verify_photos.py`, `make_matrix*.py` |
| Agentes | `.claude/agents/qa-planner.md`, `qa-runner.md`, `qa-analyst.md` |
| Documentación de QA | `docs/qa/INVENTARIO-PRUEBAS.md`, `docs/plan/plan-agentes-qa-automatizado.md`, `docs/guia-conexion-adb-wifi.md` |

**Qué está validado y qué falta (estado del origen):** validado en Android 10, 14 y 16; recetas de texto, dropdown, dropdown con búsqueda, typeahead, checkbox, grupos y archivos. **Por construir en el nuevo proyecto:** recetas de radio, `FlowRunner` declarativo y descubrimiento asistido, `onPermissionRequest`/geolocalización del WebView, ritmo/anti-abuso en producción (en el origen solo existe en el modo prueba), y ocultar menús de prueba en release.
