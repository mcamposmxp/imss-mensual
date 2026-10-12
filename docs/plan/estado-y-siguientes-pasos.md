# Estado y siguientes pasos

## F0 — Esqueleto: cerrada (2026-10-10)
Evidencia: `docs/qa/2026-10-10-v0.1.1-f0-arranque-reinicio-GUE6FMTOCYGA5TPV.log`. Sin login: «sesión persiste» = la app reabre y carga la URL.

## F1 — Sondas y esquema: en revisión (v0.2.1, versionCode 5)
Hecho: menú «Sondas» (general, fields, buttons, ready), disparo por adb solo en debug (`am broadcast -a com.maxipublica.imssmensual.TEST --es cmd probe --es name <x>`), salida completa en `files/probes/PROBE_*.json` (sin valores de campos). Esquema: `schemas/paso-1.json`; columnas: `docs/csv-schema.md`.
Evidencia: `docs/qa/2026-10-10-probe-paso1-{GENERAL,FIELDS,BUTTONS}.json`. Paso 1 = CURP, correo, confirmar correo, captcha, botón Continuar. Las etiquetas de la sonda coinciden con la captura de pantalla del Xiaomi.
Pendiente para cerrar: que el humano valide el `FormSchema` del Paso 1 contra la página.
No hecho a propósito: sondar pasos 2–4 requiere avanzar con un CURP real, es F3 / modo descubrimiento.

## Hallazgos del flujo real (2026-10-10, sesión de descubrimiento, v0.3.x)
- Paso 1 → Continuar → (con CURP de pruebas que ya tenía trámite del mes) la app llegó directo a `https://adodigital.imss.gob.mx/pth/RegistraDomicilio.do`, pantalla «Paso 5 Líneas de pago»: tabla con período, monto, fecha límite y columnas **Comprobante** (ícono documento), **Línea de captura** (ícono QR) y **Pago en línea** (botón «Ver bancos», `onclick=showModal(...)`, id numérico).
- Los íconos Comprobante y Línea de captura NO son `<a>`, `<button>` ni elementos con `onclick` propio (la sonda `links` no los ve). Pendiente: sonda `cells` sobre la pantalla viva para ver el manejador/destino.
- Navegar directo a `RegistraDomicilio.do` sin pasar por el flujo devuelve el Paso 1: la pantalla depende del estado del servidor.
- El volcado contiene nombre de persona y tokens: los `*-LINKS.json` y `*-CELLS.json` están en `.gitignore` y el existente fue redactado.
- Acción final acordada para esta pantalla: descargar Comprobante y Línea de captura y enviarlos con la app de correo de Android (destinatario de prueba en CLAUDE.md); el humano pulsa Enviar. No implementado aún.
- Instalar una versión nueva mata la app y pierde esta pantalla: no reinstalar durante una exploración.

## Etapa final (Paso 5 · Líneas de pago): PROBADA de punta a punta (2026-10-10, v0.6.1)
Evidencia del log del dispositivo (Xiaomi 2412DPC0AG, Android 16):
- Guarda por controles (`[onclick*='showPDF']`, `[onclick*='showLineCaptura']`); el texto «Líneas de pago» NO sirve (depende del diseño responsivo, UA móvil/PC).
- Clic por texto de encabezado («Comprobante», «Línea de captura») sobre `<span class="glyphicon…" onclick="showPDF(id)">` / `showLineCaptura(id)`. Cada uno hace `POST /pth/finalizaSolicitud.do` y el sitio entrega un blob; en WebView sale como `DOWNLOAD … blob:` (sin URL descargable desde Kotlin).
- Captura en la página (hook de `URL.createObjectURL` + `HTMLAnchorElement.click`) → puente → PDF real (`head=255044462d312e34`, «%PDF-1.4»): Comprobante.pdf 75 865 bytes, LineaCaptura.pdf 35 852 bytes, guardados en cache privada.
- Todo o nada; abre el correo de Android con ambos adjuntos y destinatario desde `files/config.json` (fuera de git). El humano pulsa Enviar.
- Confirmado por el humano: el correo se envió y los documentos eran los esperados (no verificado por Claude: no hay acceso a la bandeja).
- v0.6.2: el selector se restringe a apps de correo (`selector = mailto:`) — SIN verificar en el dispositivo todavía.
- Advertencia abierta: el POST se llama `finalizaSolicitud.do`; no está confirmado si registra algo en el servidor para un CURP sin trámite previo. Las pruebas usaron un CURP que ya tenía trámite del mes.

## Pendiente
- Pasos 2, 3 y 4 (persona empleadora, persona trabajadora, confirmación): sondear, esquema, mapeo y recetas (dropdown, código postal con «Buscar», calendario de 21 días hábiles). El manual en PDF de `docs/` (20 págs., fuera de git por datos personales) cubre el recorrido; leídas solo las págs. 1–4.
- Verificar el selector de correo restringido (v0.6.2), la barra desplazable con un dedo y el ícono del launcher.
- Localizar campos por etiqueta: hoy entran por id (`via=id`).
- Confirmar paquete `com.maxipublica.imssmensual`.

## Envío por WhatsApp (v0.7.0, 2026-10-10)
- Menú «Llenar ▾ → Paso 5. Líneas de pago ▸ → Enviar por correo / Enviar por WhatsApp» (con diálogo de confirmación). El botón suelto «Documentos» se eliminó.
- WhatsApp: `ACTION_SEND_MULTIPLE` application/pdf con `setPackage` (com.whatsapp y/o com.whatsapp.w4b, declarados en `<queries>`); el contacto lo elige el humano (WhatsApp no permite prellenarlo con adjuntos) y el humano pulsa Enviar.
- Confirmado por el humano: se envió a un contacto elegido por él y funcionó. (Evidencia de la app: ver log `[docs]`.)

## Captcha del Paso 1 con Anti-Captcha (v0.8.0, 2026-10-11)
Evidencia del log del dispositivo: `getBalance` → clave válida; imagen capturada 220x40 (canvas del `<img>` mostrado, sin volver a pedirla); `ImageToTextTask` resuelta en 5 s, len=5, costo 0.0006 USD; valor releído verificado (P4). Confirmado por el humano: el sitio aceptó el captcha al pulsar Continuar (1 prueba; la tasa de acierto real aún no está medida).
Salvaguardas: clave solo en `.env`/`files/.env` (600), nunca en log (enmascarado hex-32); https solo a api.anti-captcha.com sin redirecciones; tope diario 0.50 USD; sin reintentos automáticos; sin clave → el humano.
Pendiente: medir tasa de acierto con más pruebas; usar `reportIncorrectImageCaptcha` (solo dentro de 60 s tras terminar la tarea) si el sitio rechaza; revisar términos de uso del portal del IMSS.
