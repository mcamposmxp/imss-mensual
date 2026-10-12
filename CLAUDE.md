# CLAUDE.md — Guía para Claude

Lee este archivo antes de trabajar. Guía base de diseño, reglas y lecciones: `docs/plan/guia-reutilizable-automatizacion-formularios-webview.md` (principios P1–P10 del capítulo 0, no negociables).

## Proyecto y alcance
App Android nativa (Kotlin + Jetpack Compose) en `android-app/` que, dada una URL inicial y un CSV, explora el formulario con sondas DOM, lo llena y sigue los pasos hasta el objetivo: llenar todos los pasos, seleccionar 21 días hábiles en el calendario y **detenerse antes de aceptar términos y obtener constancia y pago**. Sitio objetivo: `https://adodigital.imss.gob.mx/pth/editarTrabajador.do` (IMSS).

## Decisiones y límites obligatorios
- Kotlin nativo; sin migrar a Expo/Flutter/iOS sin decisión explícita.
- Nunca ejecutes la acción final irreversible (aceptar términos y obtener constancia/pago) sin confirmación humana explícita tras completar el formulario. La acepta el usuario del dispositivo, a mano.
- **Las pruebas nunca ejecutan el paso posterior a seleccionar el calendario y aceptar términos.** Solo se obtiene un documento final por CURP al mes. El modo prueba lo bloquea (`UiRegistry.blocked`).
- Captcha del Paso 1: lo resuelve el servicio **Anti-Captcha** (`ImageToTextTask`) desde la app; sin clave en `files/.env` lo resuelve el humano. Decisión del humano (2026-10-11); los términos de uso del sitio siguen sin revisarse a fondo. Un captcha distinto al del Paso 1, rechazado dos veces, aviso inesperado, bloqueo o pantalla desconocida es señal de alerta: pausar la sesión y reportar; no insistir. Un humano sigue pulsando «Continuar».
- Casilla «Manifiesto bajo protesta de decir verdad» (Paso 2): decisión explícita del usuario (2026-10-11) de que la marque la automatización; el log lo registra. El usuario sigue pulsando «Continuar» en cada paso.
- La clave `ANTICAPTCHA_KEY` vive en `.env` (fuera de git, permisos 600) y se copia al teléfono en `files/.env`. NUNCA leerla, registrarla ni versionarla; el log enmascara cadenas hex de 32 caracteres. Solo https a `api.anti-captcha.com`; tope diario de gasto 0.50 USD por defecto (`ANTICAPTCHA_DAILY_CAP_USD`); sin reintentos automáticos.
- Subida de archivos «cero toques» (si aparecen adjuntos): JS localiza + `scrollIntoView`; Kotlin simula el toque nativo; el `WebChromeClient` entrega los archivos. `input.click()` por JS no sirve.
- UA del WebView configurable (por omisión de escritorio).
- Localiza controles por TEXTO de etiqueta (español de México), nunca por clases CSS. El nombre técnico del campo (`name`/`id`) es solo respaldo.
- No uses `pauseTimers()` global; usa `onPause`/`onResume` por instancia.
- Solo se crean trámites nuevos (sin actualizar ni borrar). No hay marcador de correspondencia en texto libre.
- No leas, registres ni versiones credenciales ni la sesión del navegador (`auth/`, `.env`, `*.keystore` en `.gitignore`). No escribas CURP ni correo en logs de producción.

## Flujo de trabajo
1. Lee completo el archivo relevante antes de modificarlo (DOM y orquestador son sensibles).
2. Diagnostica con evidencia (logcat `ImssForm` y sondas DOM). No afirmes que funciona sin el log del dispositivo.
3. Si se instala un build, incrementa `versionCode` y `versionName` en `android-app/app/build.gradle.kts`.
4. Compila desde `android-app/` con JDK 17: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; ./gradlew assembleDebug`.
5. Pruebas reales: el humano actúa en el dispositivo y tú verificas `ImssForm` por adb con `-s <IP>:5555`.
6. El CSV se construye con los hallazgos de las sondas; documenta cada columna en `docs/csv-schema.md`. Si cambia `data/input.csv`: `cp data/input.csv android-app/app/src/main/assets/inventory.csv` y recompila.
7. Una fase por sesión como máximo. Al cerrar cada fase actualiza `docs/plan/estado-y-siguientes-pasos.md` y `memory.md`. No avances de fase sin aprobación del humano.
8. No hagas commit ni push salvo petición. Mensajes de commit descriptivos en español.

## Decisiones del proyecto (respuestas del capítulo 12)

**Objetivo y sitio**
1. Sitio/URL inicial: `https://adodigital.imss.gob.mx/pth/editarTrabajador.do`. Objetivo: llenar los pasos, seleccionar 21 días hábiles del calendario, aceptar términos y obtener los documentos de constancia y pago.
2. Acción final irreversible: aceptar términos y obtener constancia y pago. La confirma el usuario del dispositivo, después del formulario. Automatizarla por completo es una decisión futura que debe documentarse explícitamente.
3. Términos de uso del sitio: desconocidos. La decisión de automatizar la asume el usuario.
4. API oficial: no existe.

**Acceso**
5. No hay login con sesión. Se capturan CURP y correo en el formulario; hay captcha inicial, resuelto por Anti-Captcha (decisión del 2026-10-11; verificado en el dispositivo: el sitio lo aceptó). Un captcha distinto del inicial = alerta, pausar.
6. Idioma/región: solo español de México (sin etiquetas bilingües).

**Datos**
7. CSV: se construye según los campos y pasos que se vayan encontrando; esquema en `docs/csv-schema.md`. Origen local (por definir al avanzar); volumen por lote pendiente.
8. Correspondencia: por etiqueta del campo y nombre técnico; se completa conforme se descubra el flujo.
9. Adjuntos: si existen, documentar formatos, peso y origen al encontrarlos.
10. Mapas de valores: se arman con los hallazgos de las sondas.

**Operación**
11. Solo crear trámites nuevos. Campos inmutables: ninguno identificado aún; revisar con las sondas (F1).
12. Sin tope diario de trámites definido por el sitio, pero **un documento final por CURP al mes**: las pruebas evitan el paso posterior al calendario y a aceptar términos. Captcha distinto del inicial = pausar la sesión.
13. Usuarios: el propietario y dos personas más. Distribución por APK directo. Menús de prueba ocultos en release.

**Pruebas**
14. Registro banco: un CURP de pruebas guardado en `data/PRIVADO.md` (fuera de git, dato personal). Nunca llegar al paso final de obtener documentos nuevos con él.
15. Dispositivo inicial: Xiaomi X7 Pro (MIUI/HyperOS: ver «INSTALL_FAILED_USER_RESTRICTED» y la depuración USB de seguridad, cap. 9.2). Versión de Android, tamaño y conexión adb pendientes; cubrir ≥ 2 dispositivos antes de F5 (P8).
16. Tope de escrituras reales por día en pruebas: 10.
17. Agentes QA: `qa-planner` (Sonnet), `qa-runner` (Haiku), `qa-analyst` (Sonnet); orquestador con el modelo más capaz.

## Hallazgo documentado: pantalla final alterna (2026-10-10)
Si el CURP ya tiene un trámite registrado en el mes, el sitio no deja generar un documento nuevo hasta el mes siguiente y lleva a una pantalla final con los documentos **«Comprobante»** y **«Línea de captura»**. Esa es la pantalla a la que se debe llegar al terminar las 3–4 etapas. Acción final decidida por el humano para esa pantalla: **descargar ambos documentos y enviarlos por correo** (destinatario configurable en `data/config.json`, fuera de git). Verificado en el dispositivo (v0.6.1): la app captura ambos PDF (blob) y abre el correo de Android con los adjuntos; el humano pulsa Enviar.

## Valores resueltos y pendientes
- TAG de logcat: `ImssForm`. Paquete propuesto: `com.maxipublica.imssmensual` (pendiente de confirmar).
- IP del dispositivo: pendiente (`<IP>:5555`). JDK 17: `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`. adb: `~/Library/Android/sdk/platform-tools/adb`.
- Registro banco: ver punto 14 y `data/PRIVADO.md`.

## Referencias
`docs/plan/guia-reutilizable-automatizacion-formularios-webview.md` · `docs/csv-schema.md` · `docs/plan/estado-y-siguientes-pasos.md` · `memory.md` · `docs/guia-conexion-adb-wifi.md` · `docs/qa/INVENTARIO-PRUEBAS.md` (los últimos se crean cuando haga falta)
