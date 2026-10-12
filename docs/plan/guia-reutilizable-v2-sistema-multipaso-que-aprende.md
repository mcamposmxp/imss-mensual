# Guía reutilizable v2: sistema Android que aprende, junto con la persona usuaria, a explorar y llenar formularios web multipaso

> **Versión 2.0 · 2026-10-12.** Reemplaza a la [v1](guia-reutilizable-automatizacion-formularios-webview.md), que queda como historial.
> **Origen.** Destila lo aprendido en dos proyectos reales: una app Android que llenaba el formulario de un marketplace y una segunda app que recorre un trámite gubernamental de **cinco pantallas** con captcha, calendario, ventanas modales, sesión que caduca y descarga de PDF. El contenido es **genérico**; el caso real aparece solo como ejemplo en el [Anexo D](#anexo-d-caso-trabajado-trámite-multipaso-sin-datos-personales), sin datos personales.
> **Lectores.** (1) Una IA (Claude Code) que arranca un proyecto nuevo; (2) las personas que la supervisan. Cada capítulo trae **reglas** (imperativas), el **porqué** y una **checklist**.
> **Etiquetas de evidencia usadas en todo el documento:** ✅ verificado con el log o la captura del dispositivo · 🟡 implementado pero sin verificar · ⏳ pendiente · ❌ descartado.

---

## 0. Cómo usar este documento

**Para la IA que arranca un proyecto nuevo:**
1. Lee los capítulos 0, 2, 8, 12 y 14 completos **antes de escribir código**.
2. Haz las preguntas del capítulo 17 al humano, **en bloques**, y espera respuestas.
3. Crea `CLAUDE.md` desde el [Anexo A](#anexo-a-plantillas) y registra las respuestas como «Decisiones del proyecto».
4. Sigue las fases del capítulo 16. No avances de fase sin cumplir su criterio de salida **y sin aprobación del humano**.
5. Trata los fragmentos del [Anexo B](#anexo-b-fragmentos-de-código-generalizados) como punto de partida: **verifica cada receta contra el sitio real con sondas** antes de darla por buena.

### Principios (no negociables)

| # | Principio |
|---|---|
| P1 | **Sin evidencia no hay «funciona».** Afirmar un resultado exige leer el log o el JSON del dispositivo, o ver la captura. Lo que se infiere se **marca como inferencia**. |
| P2 | **Diagnosticar con datos.** Primero una sonda o un log, después el arreglo. Nunca arreglar por suposición. |
| P3 | **Localizar por texto de etiqueta** (con el `id` solo como respaldo documentado), nunca por clases CSS generadas. |
| P4 | **Verificar el valor aplicado** releyéndolo, no un efecto colateral («se cerró el diálogo»). |
| P5 | **Cerrar siempre lo que se abre** (diálogos, menús), aunque el paso falle. |
| P6 | **Confirmación humana antes de lo irreversible.** Las acciones que concretan un trámite las pulsa una persona, salvo decisión explícita, escrita y con fecha del usuario. |
| P7 | **El oráculo de pass/fail vive en la app**, no en el agente que ejecuta la prueba. |
| P8 | **Probar en más de un dispositivo y tamaño de pantalla.** |
| P9 | **Cada build instalado sube `versionCode`/`versionName`** y la app **muestra** versión y fecha de instalación. |
| P10 | **Nunca leer, registrar ni versionar credenciales, claves ni la sesión del navegador.** |
| **P11** | **Los datos personales no salen de la app ni del teléfono**: se cifran en reposo, se enmascaran en logs y se excluyen de git **por patrón**, antes de crearlos. |
| **P12** | **Todo paso tiene guarda de pantalla por controles estables** (no por URL ni por títulos): la misma URL puede mostrar pantallas distintas. |
| **P13** | **Un campo obligatorio sin dato detiene el llenado** (no se omite en silencio); un campo opcional vacío **no se toca**. |
| **P14** | **No reinstalar ni recargar a mitad de una exploración**: se pierde la pantalla del servidor. Explorar → documentar → después instalar. |
| **P15** | **El sistema aprende con la persona, no en su lugar**: propone, la persona aprueba, el motor ejecuta. Lo aprendido se guarda como archivos versionables. |
| **P16** | **Documentar mientras se avanza**: hallazgo → documento → commit. Un hallazgo que solo vive en la conversación se pierde. |

---

## 1. Qué cambió respecto a la v1

| Tema | v1 | v2 |
|---|---|---|
| Alcance | Un formulario (a veces dos pasos) | **Trámites multipaso** con sesión, captcha, calendario y modales |
| Datos | Un CSV empaquetado | **Almacén local cifrado** con pantalla de captura, pares y archivos de prellenado |
| Exploración | Sondas sueltas leídas a mano | **Ciclo de aprendizaje híbrido**: paquetes de evidencia (sondas + capturas + logs), diffs de sondas, propuesta de esquema aprobada por la persona |
| Pantalla desconocida | STOP + sonda | STOP + **paquete de evidencia completo** y propuesta de esquema |
| Sesión | No considerada | **Caduca por inactividad**: medirla, mostrar contador, recorrer sin pausas |
| Captcha | «Lo resuelve el humano» | Opción documentada de **servicio externo**, con salvaguardas |
| Descargas | Solo subida de archivos | **Descarga de PDF por blob** y envío por correo/WhatsApp con el humano |
| Compuertas humanas | Una (acción final) | Clasificación de acciones y **lista de botones que nunca se pulsan** |
| UX | Menú de sondas | Pantalla de datos, barra adaptativa, mensaje de estado visible, menú generado por esquemas |

---

## 2. Visión y ciclo de aprendizaje

### 2.1 Qué es el sistema

Una app Android (Kotlin + Compose + WebView) que **recorre un formulario web multipaso** con datos guardados en el teléfono. Para cada pantalla nueva **aprende junto con la persona**: captura evidencia, propone cómo llenarla, la persona aprueba, y el resultado queda como archivos reutilizables (`schema`, `mapping`, `flow`). En corridas posteriores no hace falta volver a explorar.

### 2.2 El ciclo (híbrido: app + Claude fuera de la app)

```
 ┌─────────────── EXPLORAR (persona navega) ───────────────┐
 │ 1. La persona llega a una pantalla nueva                 │
 │ 2. APRENDER: la app genera un PAQUETE DE EVIDENCIA       │
 │    (captura + sondas + log + metadatos)                  │
 │ 3. La app/Claude PROPONEN un esquema borrador            │
 │ 4. La persona APRUEBA o corrige (qué dato va en qué campo)│
 │ 5. Se escriben/ajustan recetas si el tipo de control es nuevo │
 │ 6. DRY-RUN: llenar + releer + informar (sin avanzar)      │
 │ 7. La persona avanza (Continuar) y se repite en la siguiente │
 └───────────────────────────────────────────────────────────┘
        Resultado: schema + mapping + (después) flow versionados en git
```

- **Dentro de la app (siempre disponible):** botón *Aprender esta pantalla* (sondas estándar + captura + log + proposición de tipos), pantalla de datos, llenado por esquema, mensaje de estado.
- **Fuera de la app (Claude):** lee los paquetes, afina el esquema, escribe recetas nuevas, valida con el log del dispositivo y documenta.
- **Por qué híbrido:** la detección de tipos por heurística es buena para controles estándar pero falla con componentes propios (calendarios, filas seleccionables, modales). La persona resuelve la ambigüedad; Claude escribe la receta **una vez**.

### 2.3 Qué aprende el sistema (y dónde se guarda)

| Aprendizaje | Archivo | Quién lo aprueba |
|---|---|---|
| Qué controles tiene una pantalla y su tipo | `schemas/<pantalla>.json` | La persona |
| Qué dato va en cada control | `mappings/<pantalla>.map.json` | La persona (mapeo aprobado) |
| Cómo se reconoce la pantalla y cómo se avanza | `flows/<trámite>.json` | La persona |
| Valores constantes (decisiones, reglas por defecto) | `constants/<proyecto>.json` | La persona |
| Tiempos observados (sesión, esperas) | `docs/…/hallazgos.md` | Claude con evidencia |

**Regla:** un mapeo no aprobado nunca se ejecuta con datos reales.

**Checklist cap. 2:** [ ] Ciclo implementado en etapas independientes · [ ] Cada aprendizaje en un archivo versionado · [ ] Nada se ejecuta sin aprobación humana del mapeo.

---

## 3. Arquitectura v2

### 3.1 Componentes

| Componente | Responsabilidad |
|---|---|
| `MainActivity` (Compose) | Hospeda el WebView, barra de acciones, pantalla de datos, menú generado por esquemas, mensaje de estado, ciclo de vida |
| `configureWebView` | Settings, cookies, clientes, puente JS, traza de red de solo lectura, listener de descargas |
| Puente `postMessage` | **Único canal JS→Kotlin**: JSON con campo `type` |
| `DataModel` / `DataStore` | Entidades (p. ej. patrón y empleado), alta/edición/borrado, almacén **cifrado** (Keystore AES-GCM) |
| `FormFiller` | Orquesta un paso: guarda de pantalla → recorrer campos del esquema → informar |
| `FillScripts` | Recetas JS por tipo de control |
| `ProbeScripts` | Sondas de **solo lectura** |
| `EvidenceBundle` (nuevo) | Genera el paquete de evidencia: capturas + sondas + log + metadatos |
| `SchemaProposer` (nuevo) | Heurísticas para proponer tipos y mapeos |
| `FlowRunner` | Ejecuta el flujo declarativo con guardas, reloj de sesión y compuertas humanas |
| `CaptchaSolver` (opcional) | Cliente del servicio externo (cap. 9) |
| `DocCapture` | Captura de blobs de descarga (cap. 10) |
| `TestHarness` (solo debug) | Receptor de comandos adb: `fill`, `probe`, `par_set`… |

### 3.2 Decisiones firmes

- **Kotlin nativo + WebView.** El toque nativo cuenta como *user activation*; `input.click()` por JS no abre selectores de archivos.
- **Un solo canal JS→Kotlin**, mensajes tipados. Un script = una respuesta.
- **Scripts JS autocontenidos** (IIFE async) inyectados con `evaluateJavascript` y esperados con un `CompletableDeferred` + timeout.
- **Log a logcat con una etiqueta propia**, espejado en pantalla; **el último mensaje se muestra bajo la barra** (ahorra abrir logcat).
- **Los esquemas viajan como assets** (`schemas/` y `mappings/` en la raíz del repo, enlazados al build). **Cada `paso-N.json` genera una entrada del menú** automáticamente.
- **JS dentro de Kotlin no se valida al compilar**: validar su sintaxis con `node` (Anexo C).

### 3.3 Estado de la app

```
datos (cifrados) ─┐
esquema + mapeo ──┼─► FormFiller ──► WebView (sitio)
constantes ───────┘        │
                           └─► log ImssForm / captura / paquete de evidencia
```

**Checklist cap. 3:** [ ] Un único canal de mensajes · [ ] Menú generado por esquemas · [ ] Log con etiqueta propia y mensaje visible · [ ] Sintaxis JS validada con node.

---

## 4. Datos: almacén, prellenado y constantes

### 4.1 Pantalla de datos (captura local)

**Regla:** los datos que alimentan los formularios **se capturan en la app**, en pantallas **nativas** (no en el WebView del sitio), y se guardan cifrados.

- **Modelo.** Entidades con **lista anidada** cuando la relación es natural (un patrón con sus empleados). El par activo se elige tocando al hijo; el encabezado de la app muestra `Entidad A → Entidad B`.
- **Acciones:** agregar, editar, borrar (con confirmación) por cada entidad.
- **Campos = los que pide cada paso.** Un campo «nombre para mostrar» es **local** (el sitio no lo pide) y evita guardar el nombre real.
- **Validación local** antes de guardar: formato de identificadores (longitud y patrón), teléfono, código postal, correo.
- **Cifrado en reposo:** AES-GCM con una clave del Android Keystore (no exportable). Archivo = `[longitud_iv][iv][cifrado]`. Si el descifrado falla, **se aparta el archivo** (`.bak`) y se avisa; nunca se sobrescribe en silencio.
- **Fuera de las copias de seguridad** (`allowBackup=false`).
- **Importación para pruebas** por adb (`import_csv`), con el archivo en claro **borrado tras importar**.

### 4.2 Archivos de prellenado y constantes

Hay datos que **no cambian entre trámites** o que son decisiones del proyecto. Se separan de los datos por persona:

| Tipo | Ejemplo | Dónde vive |
|---|---|---|
| Datos por persona | identificadores, domicilio, salario | Almacén cifrado (pantalla de datos) |
| Constantes del trámite | «mes de incorporación = actual», «días = 21», regla del calendario | `constants/<proyecto>.json` (versionado, sin datos personales) |
| Decisiones de consentimiento | «la automatización marca el manifiesto» | `CLAUDE.md` + campo `const` en el mapeo, con fecha |
| Secretos | clave de un servicio externo | `.env` fuera de git, copiado al teléfono con permisos 600 |

**Precedencia al llenar:** dato de la persona > constante del trámite > valor por omisión del esquema. Un valor vacío **no se escribe** (P13).

### 4.3 Transformaciones y mapas de valores

`upper`, `lower`, `digits`, `decimal`, y valores calculados (p. ej. nombre del mes en español a partir de «actual/siguiente»). **Los valores de listas (colonia, subdelegación…) deben coincidir con el texto del sitio**: comparar sin acentos ni mayúsculas, con coincidencia exacta primero y única-contenida después; si no, fallo tipado `opcion_no_encontrada` con la **muestra de opciones en el log** (un catálogo público, no un dato personal).

**Checklist cap. 4:** [ ] Pantalla de datos nativa · [ ] Cifrado Keystore · [ ] Constantes separadas de los datos · [ ] Importación que borra el archivo en claro · [ ] Validación local.

---

## 5. Exploración: sondas, diffs, capturas y logs

### 5.1 Catálogo de sondas (solo lectura)

| Sonda | Qué extrae | Para qué |
|---|---|---|
| `general` | URL, título, conteos de controles, títulos, iframes, mención de captcha | Primer vistazo |
| `fields` | Cada input/select/textarea: `tag,type,id,name,label,maxlength,visible,disabled,readonly,valueLen,options` (**sin valores**) | Esquema de campos |
| `buttons` | Botones (`button`, `input[type=button]`, `[role=button]`) con texto, id, estado | Acciones |
| `choices` | Radios/casillas/switches agrupados por `name` | Grupos de opciones |
| `dialogs` | Modales/listas/menús visibles y sus opciones | Ventanas |
| `cells` | Tablas: celdas, hijos, manejadores **y la fila `<tr>`** (clase, id, color) | Calendarios, renglones seleccionables |
| `links` | Enlaces y elementos con `onclick`, **URLs sin query** | Descargas, navegación |
| `text` | Títulos, etiquetas visibles y texto principal (puede contener datos: **fuera de git**) | Documentar la pantalla |
| `fn` | Código fuente enmascarado de funciones del sitio (`showPDF`…) | Entender descargas/acciones |
| `hook` | Observa `window.open`, `fetch`, XHR, `form.submit`, iframes nuevos | Efectos de un clic |
| `capture` | Intercepta blobs de descarga | Capturar PDF |
| `ready` | Marcadores de «pantalla lista» (texto + controles) | Guardas |

**Reglas de las sondas:** solo lectura; no volcar **valores** de campos (solo longitud); enmascarar argumentos largos de `onclick`; salida a archivo (logcat trunca ≈ 4 KB) y troceada al log; cada sonda envía **un** mensaje `PROBE_*`.

### 5.2 Diff de sondas (descubrir lo que aparece al interactuar)

Los formularios progresivos esconden campos, habilitan controles y cargan listas **después** de una acción (consulta, selección, casilla). Técnica:

1. Sondear la pantalla (`A`).
2. Provocar la acción (la persona o una receta).
3. Sondear otra vez (`B`).
4. **Comparar `A` y `B`:** controles que pasaron a *visible*, que se *habilitaron*, listas cuyo conteo de opciones creció, campos de solo lectura que ganaron valor (`valueLen`).

Esto revela **dependencias** (colonia → subdelegación, consulta de identificador → datos de solo lectura, tipo de pago → calendario) sin leer el código del sitio. Documenta cada dependencia en el esquema (`depends_on`, `only_if`, `lookup.fills`).

### 5.3 Paquete de evidencia (🟡 por construir)

Cada vez que la persona pulsa *Aprender esta pantalla*, la app crea una carpeta en almacenamiento privado:

```
evidence/<AAAA-MM-DD_HHMMSS>-<pantalla>/
  manifest.json        # versión de la app, dispositivo, ruta (sin query), hora, antigüedad de la sesión, paso anterior
  screen.png           # captura del WebView (sin la barra de la app)
  probe-general.json
  probe-fields.json    # sin valores
  probe-buttons.json
  probe-choices.json
  probe-cells.json
  probe-dialogs.json
  log.txt              # los últimos N mensajes del log ya enmascarados
  proposal.json        # esquema borrador propuesto (cap. 6)
```

- **Capturas.** Desde la app: `PixelCopy` o `view.draw` del WebView. Desde el Mac: `adb exec-out screencap -p`. Las capturas pueden contener datos personales → **fuera de git**; si hay que citarlas en un documento, redactar.
- **Exportación.** `adb pull` desde el almacenamiento privado (`run-as`) o compartir el paquete como archivo comprimido.
- **Por qué sirven:** Claude lee **todo junto** (qué se veía, qué controles había, qué decía el log) y puede proponer el esquema sin que la persona describa nada.

### 5.4 Logs que facilitan el trabajo

- **Un mensaje por acción**, con prefijo `[campo]`, resultado (`OK`/`FALLO`) y **longitudes en vez de valores**.
- **Mensajes de fallo tipados:** `etiqueta_no_encontrada`, `opcion_no_encontrada` (+ muestra), `opcion_ambigua`, `valor_no_aplicado`, `control_deshabilitado`, `pantalla_desconocida` (+ qué marcador falta), `campo_requerido_vacio`, `boton_prohibido`, `timeout`.
- **Traza de red de solo lectura:** `método + host + ruta` (sin query, sin cabeceras, sin cookies). Distingue «el sitio respondió» de «no pasó nada» y revela endpoints (`consulta…Ajax`).
- **Marca de tiempo** en cada línea: permite medir la caducidad de la sesión.
- **Enmascarado** de identificadores, correos y claves antes de escribir (cap. 12).

**Checklist cap. 5:** [ ] Sondas sin valores · [ ] Diff de sondas documentado · [ ] Paquete de evidencia · [ ] Fallos tipados · [ ] Traza de red sin cookies.

---

## 6. De evidencia a esquema

### 6.1 Heurísticas para proponer el tipo de control

| Evidencia | Tipo propuesto |
|---|---|
| `input[type=text|tel|email|number]`, `textarea` | `text` |
| `select` (con opciones o que las recibirá) | `select_native` |
| `input[type=radio]` agrupados por `name` | `radio` (con `options`) |
| `input[type=checkbox]` | `checkbox` |
| `input` de solo lectura que gana valor tras un botón | `readonly` + `lookup` en el campo previo |
| Botón cuyo clic hace una petición de consulta | `lookup` (botón auxiliar, **nunca** de avance) |
| `<img>` junto a un input | `captcha_image` |
| Tabla con `td[data-handler=selectDay]` (datepicker) | `calendar_days` |
| Fila `tr` que cambia de clase al hacer clic | `row_select` |
| Modal visible con botón al final del texto | `modal_accept` (**compuerta humana**) |
| Controles ocultos que se muestran con otra opción | campo con `only_if` |

La propuesta es un **borrador**: la persona confirma el tipo y a qué dato corresponde cada control. Las heurísticas se afinan con cada proyecto.

### 6.2 Formatos de archivo

**Esquema (`schemas/paso-N.json`):**
```json
{
  "screen_id": "paso-2",
  "title": "Paso 2. Persona empleadora",
  "url_pattern": "^https://sitio\\.ejemplo/app/",
  "ready": { "text_regex": "", "needs": ["#telefono", "#cp", "#colonias"] },
  "fields": [
    { "key": "telefono", "labels": ["Teléfono*:"], "kind": "text", "required": true, "order": 1, "dom": { "id": "telefono", "maxlength": 10 } },
    { "key": "cp", "labels": ["Código postal*:"], "kind": "text_lookup", "required": true, "order": 2, "dom": { "id": "cp" },
      "lookup": { "button_labels": ["Buscar"], "dom": "buscarCP" }, "reset_if_locked": { "dom": "limpiar" } },
    { "key": "colonia", "labels": ["Colonia*:"], "kind": "select_native", "required": true, "order": 3, "dom": { "id": "colonias" } },
    { "key": "dias", "kind": "calendar_days", "only_if": { "tipo_pago": "dia" }, "dom": { "id": "contador" } }
  ],
  "actions": [ { "key": "continuar", "dom": { "id": "continuar" }, "note": "NUNCA lo pulsa la app" } ],
  "never_touch": ["continuar", "cancelar", "regresar"]
}
```
**Mapeo (`mappings/paso-N.map.json`):** `{ "map": { "telefono": {"csv":"tel_celular","transform":"digits"}, "manifiesto": {"const": true} } }`. `const` = decisión explícita del usuario (se registra en el log).

**Flujo (`flows/<trámite>.json`, ver v1 cap. 6 y capítulo 8):** pasos con `detect`, `schema`, `mapping`, `advance` (quién avanza: `human` o `auto`), `max_idle_s` y compuertas.

**Constantes (`constants/<proyecto>.json`):** reglas por defecto sin datos personales.

### 6.3 Aprobación

1. La app muestra la propuesta en una pantalla nativa: *control → tipo → dato*.
2. La persona confirma, cambia o descarta cada fila.
3. Se escribe el esquema/mapeo. **Mapeo sin aprobar = no ejecutable con datos reales.**
4. Claude revisa el paquete y escribe recetas para tipos nuevos.

**Checklist cap. 6:** [ ] Propuesta con heurísticas · [ ] Aprobación humana · [ ] Archivos versionados · [ ] `never_touch` en cada esquema.

---

## 7. Recetas de llenado

Todas cumplen el **contrato**: localizar (P3) → actuar → **releer** (P4) → responder **una vez** (`FILL_RESULT`) con motivo tipado. Ninguna escribe valores en el log.

| Tipo | Qué hace | Verificación |
|---|---|---|
| `text` | Setter nativo + `InputEvent` carácter a carácter (frameworks controlados) | `el.value === valor` |
| `text_lookup` | `text` + clic en el botón auxiliar de consulta; si el campo está **bloqueado** de una búsqueda previa, pulsa antes el botón de limpiar | Los campos `readonly` dependientes quedan con valor |
| `readonly` | Espera a que el sitio rellene el campo | Longitud > 0 |
| `select_native` | Espera a que **carguen las opciones**, elige por texto normalizado | `selectedIndex` con el `value` esperado |
| `checkbox` | Clic **solo si el estado difiere** (literal booleano, nunca cadena) | `.checked` |
| `radio` | Clic si no está marcado (falla si está deshabilitado) | `.checked` |
| `calendar_days` | Marca los primeros N días hábiles del mes visible (re-consulta cada celda tras cada clic) | **Contador del sitio == N** y título del mes |
| `row_select` | Clic en el renglón (`tr`) | Clase/estilo de selección |
| `modal_accept` | Espera el modal, hace scroll interno hasta el final y pulsa **solo** el botón indicado | Modal cerrado + casilla marcada. **Compuerta humana** (cap. 8) |
| `captcha_image` | Captura el canvas del `<img>` mostrado, resuelve y escribe (cap. 9) | Valor releído |
| `file` | JS localiza, Kotlin toca (v1 cap. 2.9) | Archivos aceptados |

**Reglas:**
- Las recetas **esperan** (con tope) lo que se carga por AJAX; nunca duermen fijo.
- Un fallo de un campo **obligatorio** detiene el paso (`STOP campo_requerido_fallido`).
- Los botones de consulta (`Buscar`, `Consultar`) **no son de avance**; aun así se les aplica la lista de prohibidos (cap. 8).

**Checklist cap. 7:** [ ] Una receta por tipo · [ ] Esperas con tope · [ ] Releer siempre · [ ] Sin valores en el log.

---

## 8. Flujos multipaso: guardas, tiempos y compuertas humanas

### 8.1 Guarda de pantalla por controles (P12)

`ready` = lista de **selectores que deben existir** (`needs`), con un `text_regex` solo si es estable. Dos lecturas consecutivas OK. Razón: ✅ la **misma URL** sirvió pantallas distintas, y ✅ los títulos desaparecen con el diseño responsivo del móvil. Si la guarda falla, el log dice **qué marcador falta**.

### 8.2 Sesión que caduca (✅ observado)

- **El servidor cierra la sesión por inactividad.** Medido en el caso real: funciona a **~2,5 min**, caduca a **~9 min** (el umbral exacto no se midió).
- **Al caducar, cualquier petición devuelve la pantalla inicial**, a veces bajo otra URL. Parece «un error»; es la sesión.
- **Las sondas no renuevan la sesión** (solo leen la página). Cada petición al servidor sí lo hace.
- **Reglas:** recorrer el trámite **sin pausas largas**; mostrar un **contador de tiempo de sesión** (⏳); medir el umbral en la primera sesión y documentarlo; ante «volvió al inicio», sospechar caducidad **antes** de sospechar de la receta.

### 8.3 Clasificación de acciones y compuertas (P6)

| Clase | Ejemplos | Quién la ejecuta |
|---|---|---|
| Lectura | Sondas, capturas | App |
| Escritura reversible | Llenar campos, limpiar | App |
| Consulta | Buscar, Consultar | App |
| **Avance** | «Continuar», «Siguiente» | **Persona** (o la app **solo** con decisión explícita) |
| **Declaración legal** | Marcar manifiesto/términos | App **solo** por decisión explícita, escrita y con fecha |
| **Irreversible** | Confirmación final, enviar, pagar, borrar | **Persona. Siempre.** |

**Lista `NEVER_CLICK`:** ids de botones que **ninguna** receta puede pulsar (avance, confirmación final, cancelar, regresar). Si un esquema lo pidiera, el llenado se detiene con `boton_prohibido`.

**Detenerse con el avance habilitado:** la app informa «Continuar habilitado» y se detiene. ⚠️ **«Habilitado» no prueba que el formulario esté completo**: ✅ hay sitios que dejan el botón activo y validan al pulsar; por eso la app valida **sus propios datos** antes (P13).

### 8.4 Reglas de robustez

- Reintentos solo para lecturas y consultas idempotentes. **Nunca** reintentar automáticamente una acción irreversible.
- **Idempotencia:** repetir el llenado sobre la misma pantalla debe producir «sin cambios» (por eso se limpia un campo bloqueado antes de escribir).
- Pantalla desconocida → STOP + paquete de evidencia (cap. 5.3).
- Captcha **distinto** del esperado, rechazado dos veces, aviso o bloqueo → **alerta y pausa**.

**Checklist cap. 8:** [ ] `ready` por controles · [ ] Contador de sesión · [ ] Umbral de caducidad medido · [ ] `NEVER_CLICK` · [ ] Compuertas por clase · [ ] Datos obligatorios validados.

---

## 9. Captcha (opcional)

> ⚠️ **Resolver un captcha con un servicio externo evita un control anti-bot del sitio.** Antes: (1) revisar los **términos de uso** del sitio (los generales pueden no ser los del portal concreto); (2) la decisión es del **usuario**, por escrito y con fecha; (3) considerar la alternativa «lo resuelve una persona»; (4) el servicio recibe **solo la imagen del captcha** (sin datos personales) y la resuelven personas.

**Proveedor por omisión:** [Anti-Captcha](https://anti-captcha.com/) (tipo de tarea imagen a texto, API `https://api.anti-captcha.com/`), con la clave en la variable de entorno **`ANTICAPTCHA_KEY`** del `.env` y el tope opcional **`ANTICAPTCHA_DAILY_CAP_USD`** (por omisión 0,50). Cambiar de proveedor solo exige sustituir el cliente: el resto del diseño no depende de él.

**Diseño probado (✅):**
- **Capturar la imagen ya mostrada** (canvas del `<img>`), **no volver a pedir la URL**: pedirla de nuevo puede generar **otro** captcha distinto del visible.
- Cliente mínimo `createTask` (tipo imagen a texto, sensible a mayúsculas) + sondeo de resultado cada pocos segundos con tope. **Solo https al host del proveedor**, sin redirecciones, tiempos de conexión/lectura acotados.
- **La clave** vive en `.env` (permisos 600, fuera de git), se copia al teléfono sin leerla ni mostrarla, **nunca se registra** (el enmascarado del log cubre cadenas hex de 32).
- **Tope diario de gasto** (por defecto bajo) y contador persistente; **sin reintentos automáticos**.
- **Validar la clave** con la consulta de saldo antes de usarla (el proveedor pide ≥ 30 s entre llamadas).
- **Pedir la longitud mínima** observada. ✅ Una respuesta de 4 letras para una imagen de 5 habría sido rechazada: tras observar la longitud constante (5 de 5) se fijó `minLength=5`.
- **Revisar a ojo** el texto escrito contra la imagen antes de avanzar (la persona sigue pulsando «Continuar»).
- Registrar el **costo y el tiempo** por tarea (observado: ~5–21 s y ~0,0006 USD).
- ⏳ Informar errores al proveedor solo es posible dentro de ~60 s tras resolver: requiere saber si el sitio lo rechazó.
- **Medir la tasa de acierto** real (registrar cada intento y si el sitio lo aceptó): con pocas muestras no hay estadística.

**Sin clave configurada → el captcha lo resuelve la persona** (comportamiento por omisión).

**Checklist cap. 9:** [ ] Términos revisados y decisión escrita · [ ] Imagen del canvas, sin repetir la petición · [ ] Clave fuera de git y del log · [ ] Tope de gasto · [ ] `minLength` · [ ] Sin clave = humano.

---

## 10. Documentos descargados y envío

### 10.1 Captura de blobs (✅)

Algunos sitios generan el PDF en el servidor y lo entregan como **blob** que enlazan con un `<a download href="blob:…">` al que hacen `click()`. En un WebView eso llega como una descarga `blob:` que **Kotlin no puede pedir** (la URL solo existe en la página).

**Solución:** inyectar un hook que envuelve `URL.createObjectURL` y `HTMLAnchorElement.click`; cuando el sitio hace el clic, el hook lee el blob con `FileReader` y lo manda por el puente (base64). **No se leen cookies** (P10). Verificar que sea PDF (`%PDF`); si llega como texto base64, decodificarlo. **Todo o nada:** si falla un documento, no se envía ninguno.

### 10.2 Envío con la persona al mando

- **Correo:** `ACTION_SEND_MULTIPLE` con **selector `mailto:`** para ofrecer solo apps de correo, destinatario desde un archivo de configuración fuera de git. La persona pulsa Enviar.
- **WhatsApp:** `setPackage` a WhatsApp y/o WhatsApp Business (declarados en `<queries>`); WhatsApp **no permite prellenar el contacto** con adjuntos; la persona lo elige.
- `FileProvider` con `cache-path`; se borra la carpeta de documentos antes de cada descarga.
- **Los PDF contienen datos personales:** solo caché privada, nunca el repositorio.

**Checklist cap. 10:** [ ] Hook de blobs · [ ] Verificación `%PDF` · [ ] Todo o nada · [ ] Selector de correo restringido · [ ] Persona pulsa Enviar.

---

## 11. UX de la app

| Elemento | Regla |
|---|---|
| **Versión y fecha de instalación** (P9) | Siempre visibles bajo la barra y en el log de arranque |
| **Par activo** | Encabezado con `Entidad A → Entidad B` o «Sin par seleccionado» |
| **Mensaje de estado** | El último mensaje del log, visible; ✅ evita abrir logcat y muestra por qué falló algo |
| **Menú Llenar** | Se **genera** de los esquemas disponibles (`paso-N.json`) |
| **Barra adaptativa** | Los botones **más usados a la izquierda**: contadores guardados en el teléfono; orden de partida definido (p. ej. Datos, Llenar, Recargar, Abrir, Sondas, UA, Log). La barra se **desplaza horizontalmente** y nunca parte el texto de un botón (`maxLines=1`, `softWrap=false`) |
| **Pantalla de datos** | Nativa, anidada (entidad con sus hijos), con confirmación al borrar |
| **Insets** | Android 15+ es *edge-to-edge*: aplicar `safeDrawing` o la barra se superpone con la barra de estado |
| **Confirmaciones** | Cada acción que sale de la app (correo, WhatsApp) pide confirmar con un diálogo que resume qué hará |
| **Menús de prueba** | Ocultos en release |

**Lección de diseño (✅):** al usuario le confundió que, en la lista anidada, solo los hijos tuvieran botón de selección. Se probó un diseño de dos listas independientes y **el usuario pidió volver al anidado**, que era el correcto. **Regla:** antes de rediseñar una interfaz que el usuario ya aprobó, **mostrar el modelo con un ejemplo y confirmar**; y **commitear antes de cambiar la interfaz** para poder revertir sin reconstruir.

**Prueba de la barra adaptativa:** `adb` no puede tocar la pantalla en algunos equipos; se verifica **editando los contadores** guardados (`run-as` + `shared_prefs`), reiniciando y mirando la captura, y **se restauran a cero** al terminar.

**Checklist cap. 11:** [ ] Versión visible · [ ] Par activo · [ ] Mensaje de estado · [ ] Menú generado · [ ] Barra sin saltos de línea · [ ] Insets.

---

## 12. Seguridad y privacidad

1. **P10 y P11.** Ninguna clave, credencial ni dato personal en git, logs ni informes.
2. **`.gitignore` por patrón desde el primer día:** `.env`, `data/`, `docs/qa/*-TEXT.json`, `*-CHOICES.json`, `*-CELLS.json`, `*-LINKS.json`, PDF de manuales, archivos con datos reales.
3. **Escaneo previo a cada commit** (sin imprimir valores): ¿aparece la clave en lo preparado? (`git grep --cached -q`), cadenas hex de 32, patrones de identificadores, correos, teléfonos, domicilios, nombres y ids de trámite. **Si algo aparece, redactar** o excluir y volver a escanear.
4. **Enmascarado en el log:** identificadores, correos y claves. **No registrar** domicilios ni textos de opciones elegidas; sí longitudes.
5. **Volcados de sondas:** sin valores; el texto de pantalla y las tablas pueden traer datos → fuera de git, y si se versiona, **redactado** (nombres, ids de trámite).
6. **Almacén cifrado** (cap. 4) y `allowBackup=false`.
7. **Servicios externos:** solo https, host fijo, sin redirecciones, tiempos acotados, sin enviar más de lo estrictamente necesario.
8. **Permisos de archivos** del `.env`: 600, también en el teléfono.
9. **Revocar y rotar** cualquier credencial que haya pasado por una terminal compartida o por el historial de la shell; limpiar el historial.
10. **Términos de uso** del sitio objetivo: revisarlos; la decisión de automatizar es del usuario (cap. 17).

---

## 13. Pruebas, adb y dispositivos

### 13.1 Arquitectura de pruebas (extiende v1 cap. 8)

- Receptor de comandos **solo en debug**, un único receptor activo por proceso. Comandos útiles: `fill`, `probe`, `docs`, `click`, `goto`, `import_csv`, `par_list`, `par_set`, `captcha_balance`.
- **Oráculo en la app** (P7): el resultado lo decide el log/JSON del dispositivo, no el agente.
- **Prueba de punta a punta del flujo:** llenar → verificar por log → capturar pantalla → comparar contra el paquete de evidencia.
- **Orden de una corrida:** instalar → importar datos → activar el par → recorrido rápido (sesión) → leer log → documentar.

### 13.2 Problemas conocidos (suma a v1 cap. 9)

| Síntoma | Causa / solución |
|---|---|
| `adb: device not found` tras horas | El servidor adb se cuelga: `adb kill-server; adb connect <IP>:5555`; el modo `tcpip` se pierde al reiniciar el teléfono |
| `ping` sin respuesta y sin USB | El teléfono está dormido o cambió de red: pedir conectar por USB o la IP actual |
| `INSTALL_FAILED_USER_RESTRICTED` (Xiaomi) | Activar «Instalar vía USB» y la depuración de seguridad; instalar por USB; luego WiFi funciona |
| `adb input swipe/tap` falla (`INJECT_EVENTS`) | Xiaomi lo bloquea: las acciones táctiles las hace la persona; probar con comandos de la app |
| `am broadcast … --es name "Línea de captura"` llega como `pkg=de` | `adb shell` une los argumentos: las comillas deben ir **dentro** del comando remoto (`adb shell "am broadcast … --es name 'Línea de captura'"`) |
| `timeout: command not found` (macOS) | No existe: usar bucles con `sleep` o ejecutar en segundo plano |
| Un comando de `adb` se queda esperando | Lanzarlo en segundo plano y comprobar; matar el proceso colgado |
| La barra de la app tapa la barra de estado | Android 15+: insets |
| Un script JS con error sintáctico no avisa | `evaluateJavascript` falla en silencio y el paso da `timeout`: validar con `node` |
| Una propiedad puesta sobre un arreglo JS se pierde al `JSON.stringify` | Insertar la información como un elemento más |

### 13.3 Dispositivos

≥ 2 versiones de Android y ≥ 2 tamaños (P8). Anotar resultados por dispositivo. ⏳ Todo lo verificado en este documento se hizo en **un** teléfono.

**Checklist cap. 13:** [ ] Receptor solo en debug · [ ] Comillas dentro del comando remoto · [ ] Validar JS con node · [ ] ≥ 2 dispositivos.

---

## 14. Proceso de trabajo con la IA

### 14.1 Reglas de oro

1. **No afirmar sin evidencia (P1).** Distinguir *observado* de *inferido*; cuando la evidencia cambia, **corregir a la persona de inmediato** (ejemplo ✅: una lectura mostró 25 días con sábados; no se reprodujo y hubo que retractar la explicación).
2. **Pedir los datos que falten**; no reutilizar datos de otra persona ni de otra prueba sin permiso.
3. **Preguntar antes de decidir lo que cambia el comportamiento** (alcance, ritmo, quién pulsa qué). Las preguntas en **bloques pequeños**.
4. **Avisar antes de acciones que pierden estado** (reinstalar, recargar) y **ofrecer elegir** cuándo (P14).
5. **Ser explícito con lo no probado**: «compila e instala» ≠ «funciona».
6. **Cerrar cada ronda con la lista de qué se hizo, qué se verificó y qué falta.**
7. **Un aviso del sistema o de una tarea terminada no es una aprobación del usuario.**
8. **Hacer el trabajo seguro sin esperar** (documentar, preparar, compilar) y **dejar para la persona** lo que requiere su mano (captcha, «Continuar», confirmaciones).

### 14.2 Rondas con el humano

`Persona navega → avisa «listo» → IA lee (sondas/log/captura) → IA responde con hechos → siguiente acción`. Mientras la persona está en una pantalla del sitio: **no tocar nada** (solo lectura) y **no reinstalar**.

### 14.3 Commits e higiene

Commit solo cuando se pide. Antes: escaneo de secretos y datos personales (cap. 12). Mensaje descriptivo en español, con el pie de atribución que el entorno indique. **Documentar → commit → push** en ese orden (P16). Un `commit --amend` es aceptable solo si **no se ha subido**.

### 14.4 Cuando falla algo

1. Leer el log **entero** del intento, no suponer.
2. Comprobar si el intento realmente ocurrió (✅ un «no funcionó» resultó ser que **no se había reintentado** tras corregir el dato).
3. Clasificar: **dato** (formato, texto de lista), **receta**, **sitio** (cambio, sesión caducada), **infraestructura** (adb, red).
4. Corregir lo mínimo y volver a verificar con la evidencia.

---

## 15. Catálogo de lecciones (continuación de la v1: L1–L28)

| # | Síntoma | Causa | Regla |
|---|---|---|---|
| L29 | Se pierde la pantalla del trámite | Reinstalar mata la app; la pantalla depende de la sesión del servidor | P14: explorar antes de instalar |
| L30 | «Continuar» devuelve al paso 1 | **Sesión caducada por inactividad** (~2,5 min bien, ~9 min caducada) | Medir, mostrar contador, recorrer sin pausas |
| L31 | La misma URL muestra el paso 1 o el paso 5 | El servidor sirve formularios distintos por estado | P12: guarda por controles |
| L32 | La guarda falla en móvil aunque la pantalla es la correcta | El título depende del diseño responsivo | Guardas por controles estables |
| L33 | Las etiquetas no localizan campos | `<label>` sin `for` | `id` como respaldo documentado; mejorar con contenedor |
| L34 | «Continuar» no hace nada | El botón está habilitado pero valida al pulsar | La app valida sus datos (P13); «habilitado» no es «completo» |
| L35 | Un campo obligatorio quedaba vacío sin aviso | Un dato vacío se omitía | `STOP campo_requerido_vacio` |
| L36 | La lista de colonias llega vacía | Se carga tras una consulta AJAX | Esperar a que haya opciones; verificar |
| L37 | «opción no encontrada» | El texto guardado no coincide con la lista del sitio | Mensaje con la muestra de opciones; el usuario corrige el dato |
| L38 | No se puede repetir el llenado | Tras buscar, el campo queda bloqueado | Pulsar «Limpiar» antes (receta `reset_if_locked`) ⏳ |
| L39 | El calendario marca de más o de menos | Re-render tras cada clic; varios calendarios en el DOM | Re-buscar la celda cada vez; tomar el calendario visible; oráculo = contador |
| L40 | No hay control para elegir un renglón | La fila es el control (clic en `tr`) | Sondear `tr` además de celdas |
| L41 | La descarga no tiene URL | El PDF es un blob generado en la página | Hook de `createObjectURL`; sin leer cookies |
| L42 | El captcha no coincide | Se volvió a pedir la imagen | Capturar el canvas del `<img>` mostrado |
| L43 | El captcha viene con una letra de menos | El solver acertó 4 de 5 | `minLength` + revisar a ojo antes de avanzar |
| L44 | `adb` manda el comando a otro paquete | Los argumentos con espacios se parten | Comillas dentro del comando remoto |
| L45 | `adb` «no encuentra» el dispositivo | Servidor colgado / modo tcpip perdido | `kill-server` + `connect`; USB si hace falta |
| L46 | Los toques por adb fallan | Xiaomi bloquea `INJECT_EVENTS` | La persona toca; la app expone comandos |
| L47 | Un script JS no responde (timeout) | Error de sintaxis silencioso | Validar con `node` |
| L48 | La información de fila no aparece en el JSON | Propiedad sobre un arreglo se pierde | Insertar como elemento |
| L49 | Diseño de pantalla rechazado por el usuario | Se cambió sin confirmar el modelo | Mostrar ejemplo; confirmar; commit previo |
| L50 | Datos personales en volcados | Textos de pantalla, nombres, ids de trámite | Redactar, `.gitignore` por patrón, escaneo previo |
| L51 | No se sabe por qué falló un paso | El log estaba solo en logcat | Mensaje de estado visible |
| L52 | Una explicación dada como hecho resultó falsa | Se infirió de una lectura | Marcar inferencias; retractarse a tiempo |
| L53 | El agente actuó ante un aviso del sistema | Un aviso de tarea terminada no es aprobación | Esperar al usuario |
| L54 | No se sabe cuánto cuesta el captcha | Sin contador | Registrar costo y tiempo por tarea; tope diario |
| L55 | El botón «habilitado» engañó | Validación en el servidor al pulsar | No confiar en `disabled` como indicador de completitud |
| L56 | `continue` en lambda no compila | Característica experimental de Kotlin | Reescribir con `if` |
| L57 | Probar la barra adaptativa sin tocar | `adb input` bloqueado | Editar contadores con `run-as` y restaurarlos |
| L58 | Datos de prueba mezclados con reales | Sin separación | Datos de prueba en pantalla de datos; real y prueba claramente rotulados |

---

## 16. Plan por fases (v2)

| Fase | Entregable | Criterio de salida |
|---|---|---|
| **G0** Esqueleto | App Compose + WebView + log con etiqueta + versión/fecha visibles + insets + `.gitignore` por patrón | Carga la URL inicial; log por adb; escaneo de datos personales en el pipeline |
| **G1** Evidencia | Sondas + **paquete de evidencia** (capturas + log) + diff de sondas + «pantalla lista» por controles | Un paquete de la pantalla 1 legible por Claude sin descripción adicional |
| **G2** Datos y llenado | Pantalla de datos cifrada + recetas base + esquema/mapeo aprobados + verificación por relectura | `dry_fill` de una pantalla con todos los campos verificados |
| **G3** Multipaso | Flujo declarativo + guardas + **contador de sesión** + compuertas humanas + `NEVER_CLICK` | Recorre N pantallas y se detiene antes de lo irreversible; umbral de sesión medido |
| **G4** Aprendizaje asistido | *Aprender esta pantalla*: propuesta de esquema + aprobación en pantalla + exportación del paquete | Una pantalla nueva se aprende y se llena sin editar código (salvo tipos nuevos) |
| **G5** Captcha y documentos | (Opcional) captcha externo + captura de blobs + envío con la persona | Documento recibido y verificado (`%PDF`), enviado con confirmación |
| **G6** QA y release | Harness adb + matrices + ≥ 2 dispositivos + build release sin menús de prueba | Inventario de pruebas en ✅/🔧 y APK firmado |

**Reglas de proceso:** una fase por sesión como máximo; al cerrar una fase, actualizar el estado y la memoria; no pasar de fase sin aprobación del humano; documentar mientras se avanza (P16).

---

## 17. Preguntas para un proyecto nuevo (amplía el cap. 12 de la v1)

**Objetivo y sitio**
1. ¿Sitio, URL inicial y **objetivo final**? ¿Cuántas pantallas y cuáles son irreversibles?
2. ¿Quién confirma lo irreversible? ¿Se permitirá alguna vez automatizarlo?
3. ¿Términos de uso del sitio revisados? ¿Quién asume la decisión?
4. ¿Hay API oficial que sustituya al WebView?

**Acceso y sesión**
5. ¿Login, 2FA, captcha? ¿La sesión caduca? **Medir la caducidad** en la primera sesión.
6. ¿Idiomas y regiones? ¿Etiquetas bilingües?

**Datos**
7. ¿Qué entidades hay (p. ej. quién contrata y a quién) y cómo se relacionan? ¿Cómo se capturan?
8. ¿Qué datos son **constantes** y cuáles por persona? ¿Quién los aprueba?
9. ¿Hay archivos adjuntos? ¿Formatos, pesos, origen?
10. ¿Qué valores de listas no coinciden con el texto del sitio?
11. ¿Cuántas entradas por lote? ¿Cuál es el identificador de correspondencia?

**Operación y límites**
12. ¿Solo crear, o actualizar y borrar? ¿Campos inmutables?
13. ¿Ritmo permitido (topes, pausas, horario)? ¿Señales que obligan a detener?
14. ¿Quién usará la app y cómo se distribuye? ¿Se ocultan menús de prueba en release?
15. ¿**Quién pulsa los «Continuar»**: la persona o la app? ¿Y los de declaración legal?

**Captcha y documentos**
16. ¿Habrá servicio externo de captcha? ¿Quién decide, cuál es el tope de gasto y dónde vive la clave?
17. ¿Se descargan documentos? ¿Cómo y a dónde se envían?

**Pruebas**
18. ¿Hay registros de prueba que se puedan modificar libremente o un entorno sandbox?
19. ¿Qué dispositivos hay y cuál es de pruebas?
20. ¿Qué modelo/agentes se usarán para planificar, ejecutar y analizar?

---

## Anexo A. Plantillas

### A.1 Fragmento de `CLAUDE.md` para un proyecto multipaso

```markdown
## Decisiones y límites obligatorios
- Las acciones irreversibles las pulsa una persona. La app NUNCA pulsa: <ids de NEVER_CLICK>.
- Declaraciones legales (<lista>): decisión explícita del usuario con fecha <AAAA-MM-DD>; el log registra que las hizo la automatización.
- La sesión del sitio caduca por inactividad (<umbral medido>): recorrer sin pausas.
- Captcha: <Anti-Captcha (por omisión) / humano>. Clave solo en .env como `ANTICAPTCHA_KEY` (600); tope diario <USD> (`ANTICAPTCHA_DAILY_CAP_USD`).
- Datos: pantalla de datos cifrada; nada personal en git, logs ni informes.
- No reinstalar ni recargar a mitad de una exploración.
- Antes de cada commit: escaneo de secretos y datos personales.
```

### A.2 Agentes de QA
Mantienen los roles de la v1 (planner → runner → analyst), con las reglas nuevas: **nunca** pasar de la compuerta humana, **detenerse** ante un captcha distinto o una sesión vencida, y **citar evidencia literal** del log.

### A.3 Manifiesto del paquete de evidencia
```json
{ "app": "0.0.0 (n)", "device": "modelo / Android", "path": "/app/paso", "captured_at": "ISO-8601",
  "session_age_s": 0, "previous_step": "paso-1", "probes": ["general","fields","buttons","choices","cells"],
  "has_screenshot": true, "log_lines": 120, "redacted": true }
```

---

## Anexo B. Fragmentos de código generalizados

> Verifica cada uno contra el sitio objetivo con sondas. Los scripts JS se inyectan con `evaluateJavascript` y responden **una vez** por el puente.

### B.1 Guarda de pantalla por controles (Kotlin + JS)
```js
// READY: ok si el texto esperado (opcional) y TODOS los selectores existen
var missing = NEEDS.filter(function(q){ try{ return !document.querySelector(q); }catch(e){ return true; } });
var textOk = RX === '' || new RegExp(RX,'i').test(document.body.innerText||'');
send({type:'READY_RESULT', ok:textOk && missing.length===0, textOk:textOk, missing:missing});
```
Exigir **2 lecturas consecutivas** OK cada 500 ms (tope 20 s).

### B.2 Select nativo por texto de opción (espera opciones)
```js
function nt(s){ return (s||'').normalize('NFD').replace(/[̀-ͯ]/g,'').replace(/[*:]/g,'').replace(/\s+/g,' ').trim().toLowerCase(); }
var t0=Date.now(); while(sel.options.length<=1 && Date.now()-t0<WAIT){ await sleep(250); }
var opts=[].slice.call(sel.options).filter(function(o){return o.value!=='';});
var m=opts.filter(function(o){return nt(o.text)===want;});
if(m.length===0) m=opts.filter(function(o){return nt(o.text).indexOf(want)!==-1;});
if(m.length!==1){ /* fallo tipado + muestra de opciones */ }
sel.value=m[0].value; sel.dispatchEvent(new Event('input',{bubbles:true})); sel.dispatchEvent(new Event('change',{bubbles:true}));
// verificar: sel.options[sel.selectedIndex].value === m[0].value
```

### B.3 Calendario jQuery UI: primeros N días hábiles
```js
var t=[].slice.call(document.querySelectorAll('table.ui-datepicker-calendar')).filter(visible).pop();
var days=[]; t.querySelectorAll('tr').forEach(function(tr){ [].slice.call(tr.children).forEach(function(td,i){
  if(td.tagName==='TD' && td.getAttribute('data-handler')==='selectDay' && i<5 && !/unselectable|other-month/.test(td.className))
    days.push(td.innerText.trim()); }); });
days=days.slice(0,N);
for (var k=0;k<days.length;k++){            // volver a buscar la celda tras cada clic
  var cell=[].slice.call(visibleTable().querySelectorAll('td[data-handler=selectDay]')).find(function(td){ return td.innerText.trim()===days[k]; });
  click(cell.querySelector('a')||cell); await sleep(150);
}
// verificar con el contador del sitio: parseInt(contador.value,10) === N
```

### B.4 Ventana de términos: desplazar y aceptar (compuerta documentada)
```js
var m=document.querySelector(MODAL); /* esperar visible */
for (var k=0;k<4;k++){ [m].concat([].slice.call(m.querySelectorAll('*'))).forEach(function(e){ if(e.scrollHeight>e.clientHeight+2) e.scrollTop=e.scrollHeight; }); await sleep(400); }
var b=m.querySelector('#'+BTN_ID);        // SOLO ese botón, dentro de ESE modal
if(!b || b.id!==BTN_ID || b.disabled){ /* fallo tipado */ }
b.click(); await sleep(1200);
// verificar: modal cerrado Y casilla marcada
```

### B.5 Captura de blob de descarga
```js
var oc=URL.createObjectURL; URL.createObjectURL=function(b){ var u=oc.call(URL,b); window.__blobs[u]=b; return u; };
var ac=HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click=function(){
  if(this.href.indexOf('blob:')===0 && this.download){ var b=window.__blobs[this.href], a=this;
    if(b){ var fr=new FileReader(); fr.onload=function(){ send({type:'DOC',name:a.download,b64:String(fr.result).split(',')[1]}); }; fr.readAsDataURL(b); return; } }
  return ac.apply(this,arguments); };
```

### B.6 Captura de la imagen del captcha mostrada
```js
var cv=document.createElement('canvas'); cv.width=img.naturalWidth; cv.height=img.naturalHeight;
cv.getContext('2d').drawImage(img,0,0);
var d=cv.toDataURL('image/png');    // si el canvas queda "tainted", FALLAR: no volver a pedir la URL
send({type:'CAPTCHA_IMG', ok:true, b64:d.split(',')[1]});
```

### B.7 Almacén cifrado (esquema)
```kotlin
// Clave AES-256 en AndroidKeyStore (GCM, sin padding, no exportable)
val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
file.writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(json.toByteArray()))
// descifrar: GCMParameterSpec(128, iv); si falla, apartar el archivo (.bak) y avisar; nunca sobrescribir en silencio
```

### B.8 Barra adaptativa por uso
```kotlin
val defaultOrder = listOf("datos","llenar","recargar","abrir","sondas","ua","log")
val usage = remember { mutableStateMapOf<String,Int>().apply { defaultOrder.forEach { put(it, prefs.getInt("uso_$it",0)) } } }
fun bump(k:String){ val n=(usage[k]?:0)+1; usage[k]=n; prefs.edit().putInt("uso_$k",n).apply() }
defaultOrder.sortedWith(compareByDescending<String>{ usage[it]?:0 }.thenBy{ defaultOrder.indexOf(it) })
  .forEach { k -> key(k) { items.getValue(k)() } }     // key() conserva el estado de cada menú al reordenar
```

### B.9 Lista de botones prohibidos
```kotlin
private val NEVER_CLICK = setOf("continuar","guarda","cancelar","regresar" /* ids de avance y confirmación final */)
// ni una receta ni un botón auxiliar de consulta puede apuntar a estos ids: STOP boton_prohibido
```

---

## Anexo C. Comandos de uso diario

```bash
# Conexión y estado
adb kill-server; adb connect <IP>:5555; adb devices -l
# Instalar y arrancar
adb -s <IP>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <IP>:5555 shell am start -f 0x24000000 -n <pkg>/.MainActivity
# Comandos de la app (comillas DENTRO del comando remoto)
adb -s <IP>:5555 shell "am broadcast -a <pkg>.TEST --es cmd probe --es name 'fields'"
# Resultado de una sonda (archivo completo, sin truncar)
adb -s <IP>:5555 shell "run-as <pkg> cat files/probes/PROBE_FIELDS.json"
# Log y captura
adb -s <IP>:5555 logcat -d -s <TAG>
adb -s <IP>:5555 exec-out screencap -p > captura.png
# Copiar un archivo privado al teléfono SIN mostrarlo
adb -s <IP>:5555 shell "run-as <pkg> sh -c 'cat > files/.env && chmod 600 files/.env'" < .env
# Validar la sintaxis de un script JS embebido en Kotlin
node -e "new Function('send','sleep','document', BODY)"
# Escaneo previo al commit (sin imprimir la clave)
git grep --cached -qF "$K" && echo ALERTA
```

---

## Anexo D. Caso trabajado: trámite multipaso (sin datos personales)

Trámite gubernamental de registro con **cinco pantallas**. Resumen de lo que se aprendió:

| Pantalla | Qué contiene | Recetas | Compuerta |
|---|---|---|---|
| 1 | Identificador y correo (x2) + captcha de imagen | `text`, `captcha_image` | La persona pulsa «Continuar» |
| 2 | Contacto + domicilio con **consulta por código postal** (rellena estado/municipio y las colonias) + subdelegación dependiente + casilla de manifiesto | `text`, `text_lookup`, `readonly`, `select_native`, `checkbox` | Manifiesto por **decisión explícita del usuario**; «Continuar» humano |
| 3 | Persona trabajadora: **consulta por identificador + NSS**, domicilio, mes, tipo de pago, salario y **calendario** (≥ 21 días) | además `radio`, `calendar_days` | «Continuar» humano |
| 4a | Tabla de **período de cobertura**: seleccionar el renglón (`tr`) | `row_select` ⏳ | «Continuar» humano |
| 4b | Lista de trabajadores → casilla de **términos** → ventana con texto largo y «Aceptar» → «Confirmación requerida» | `checkbox`, `modal_accept` 🟡 | Términos por **decisión explícita**; **«Continuar» y la confirmación final: nunca la app** |
| 5 | Documentos (PDF por blob) | `DocCapture` | La persona envía por correo/WhatsApp |

**Hallazgos clave del caso:**
- ✅ **La sesión caduca** a los ~9 min de inactividad; con ~2,5 min funciona. Al caducar, cualquier petición vuelve a la pantalla 1.
- ✅ El botón «Continuar» de la pantalla 3 está habilitado aunque falten campos; valida al pulsar.
- ✅ Los controles de descarga son íconos sin `href` con manejadores; el PDF llega como **blob**.
- ✅ Tras la consulta por código postal el campo queda **bloqueado**; hay que limpiar para repetir.
- ✅ La colonia debe coincidir con el texto de la lista para ese código postal.
- ✅ La app llenó los pasos 1–3 de forma automática y verificada (incluido `dias marcados=21 contador=21`).
- 🟡 El marcado de términos está implementado, **sin verificar** en el dispositivo.
- ⏳ Pendientes: receta del renglón, limpiar el campo bloqueado, contador de sesión, tasa de acierto del captcha, QA formal y release.

Detalles y evidencia: `docs/plan/estado-y-siguientes-pasos.md` y `docs/plan/flujo-completo-pth.md` del repositorio de origen.

---

## Anexo E. Glosario

- **Sonda:** script JS de solo lectura que describe la pantalla.
- **Paquete de evidencia:** carpeta con captura, sondas, log y metadatos de una pantalla.
- **Diff de sondas:** comparación de dos sondas antes y después de una acción para descubrir dependencias.
- **Esquema / mapeo / flujo / constantes:** archivos versionados que describen controles, datos, pasos y valores fijos.
- **Compuerta humana:** acción que solo una persona (o una decisión explícita y escrita) puede ejecutar.
- **`NEVER_CLICK`:** ids de botones que ninguna receta puede pulsar.
- **Guarda de pantalla:** comprobación por controles estables de que estamos en la pantalla esperada.
- **Oráculo:** lógica que decide pass/fail; vive en la app.
- **Blob:** objeto binario generado en la página (`blob:`), no descargable por URL desde Kotlin.
- **Sesión caducada:** el servidor descarta el estado por inactividad; las peticiones devuelven la pantalla inicial.
