# IMSS Mensual

App Android nativa (Kotlin + Jetpack Compose + WebView) que automatiza el llenado del trámite
«Incorporación de las personas trabajadoras del hogar» del IMSS
(`https://adodigital.imss.gob.mx/pth/editarTrabajador.do`) a partir de un CSV, y que al final
descarga el **Comprobante** y la **Línea de captura** y los envía por **correo** o **WhatsApp**.

> Estado: en desarrollo (v0.13.0). Probado en un Xiaomi 2412DPC0AG con Android 16.
> **Verificado en el dispositivo:** pantalla de datos cifrada, Pasos 1, 2 y 3 (incluido el calendario de 21 días
> hábiles), captcha resuelto por servicio, y la descarga de los PDF del Paso 5 con envío por correo y WhatsApp.
> **Sin verificar:** el marcado de los términos y condiciones (v0.13.0). **Pendiente:** selección automática del
> renglón del período, pulsar «Limpiar» solo, QA formal y versión release.
> Estado detallado, hallazgos y pendientes: [docs/plan/estado-y-siguientes-pasos.md](docs/plan/estado-y-siguientes-pasos.md).

## Principios

- **Un humano confirma las acciones finales.** «Continuar» y el envío del correo o de WhatsApp
  los pulsa una persona. El captcha del Paso 1 lo resuelve el servicio Anti-Captcha si hay clave;
  si no, una persona. La app no acepta términos
  ni envía nada por sí sola.
- **Sin evidencia no hay «funciona»:** cada afirmación se respalda con el log del dispositivo
  (etiqueta `ImssForm`).
- Los controles se localizan por **texto de etiqueta**, no por clases CSS.
- No se leen ni se guardan credenciales ni la sesión del navegador. CURP y correo se enmascaran
  en el log.

La guía completa de diseño y lecciones (v2, vigente) está en
[docs/plan/guia-reutilizable-v2-sistema-multipaso-que-aprende.md](docs/plan/guia-reutilizable-v2-sistema-multipaso-que-aprende.md)
(la [v1](docs/plan/guia-reutilizable-automatizacion-formularios-webview.md) queda como historial);
las reglas de trabajo, en [CLAUDE.md](CLAUDE.md).

## Requisitos

- macOS/Linux con **JDK 17** y Android SDK (compileSdk 35). Gradle se descarga con el wrapper (8.9).
- Un teléfono Android 8.0+ (minSdk 26) con depuración USB o por WiFi.
- En Xiaomi/HyperOS hay que activar «Instalar vía USB» y «Depuración USB (ajustes de seguridad)»;
  `adb input` está bloqueado en esos equipos.

## Compilar e instalar

```bash
cd android-app
export JAVA_HOME=<ruta_jdk17>
./gradlew assembleDebug
adb -s <IP>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Cada build que se instale debe subir `versionCode` y `versionName` en
`android-app/app/build.gradle.kts`. La app muestra su versión y la fecha de instalación.

> Reinstalar cierra la app y **pierde la pantalla del trámite** (depende del estado del
> servidor). No reinstales a mitad de un recorrido.

## Datos privados (fuera de git)

La carpeta `data/` no se versiona. Para usar la app se necesitan, copiados al teléfono con
`adb shell "run-as com.maxipublica.imssmensual sh -c 'cat > files/<archivo>'" < data/<archivo>`:

| Archivo | Contenido |
|---|---|
| `data/import.csv` | Opcional, para cargar datos de prueba por adb (`import_csv`). Hoy los datos se capturan en la pantalla **Datos** de la app y se guardan cifrados. |
| `data/config.json` | `{"mail_to": "<correo destino>"}` |
| `.env` (raíz) | `ANTICAPTCHA_KEY=<clave>` y opcional `ANTICAPTCHA_DAILY_CAP_USD` (0.50 por defecto). Se copia a `files/.env` con `chmod 600`. |

## Uso

1. Al abrir la app aparece **Datos**: cada patrón es una caja con sus empleados. Agrega o edita (los datos se guardan
   cifrados en el teléfono), elige un empleado (eso fija el par Patrón → Empleado) y toca **Continuar**.
2. **Llenar ▾ → Paso 1**: CURP, correo y confirmación, y resuelve el captcha. Una persona revisa y pulsa **Continuar**.
3. **Paso 2** (empleador, domicilio, manifiesto) y **Paso 3** (empleado, domicilio, cotización y calendario de 21 días
   hábiles): cada uno se llena con el menú y una persona pulsa **Continuar**. El período de cobertura se selecciona a mano.
4. **Paso 4. Términos y condiciones**: marca «He leído y acepto», pulsa el «Aceptar» de la ventana y se **detiene con
   «Continuar» habilitado**. Pulsarlo (y la confirmación final) lo hace una persona: genera el documento.
5. **Paso 5. Líneas de pago ▸ Enviar por correo / WhatsApp**: descarga los dos PDF (todo o nada) y abre la app elegida
   con los adjuntos; la persona pulsa Enviar.
6. **Sondas** vuelca la estructura del DOM de la pantalla actual (solo lectura).

> **La sesión del sitio caduca por inactividad (entre ~2,5 y ~9 min).** El recorrido hasta los términos debe hacerse
> sin pausas largas.

Por adb (solo debug): `adb shell "am broadcast -a com.maxipublica.imssmensual.TEST --es cmd <fill|probe|docs|click|goto|import_csv|par_list|par_set|captcha_balance> --es name <valor>"`.
Los argumentos con espacios o acentos van entre comillas **dentro** del comando remoto.

## Estructura

| Ruta | Contenido |
|---|---|
| `android-app/` | Proyecto Gradle (paquete `com.maxipublica.imssmensual`). |
| `schemas/`, `mappings/` | Esquema de campos y mapeo dato→campo por pantalla, pasos 1 a 4 (se empaquetan como assets; cada `paso-N.json` aparece en el menú Llenar). |
| `docs/plan/` | Guía de diseño y estado del proyecto. |
| `docs/qa/` | Evidencia de sondas y logs (sin datos personales). |
| `docs/csv-schema.md` | Esquema del CSV, construido con los hallazgos de las sondas. |
| `branding/` | Logo de la app. |
| `.claude/agents/` | Agentes de QA (planner, runner, analyst). |

## Límites y advertencias

- **Solo se crean trámites nuevos.** Se obtiene un documento final por CURP al mes; las pruebas
  no deben avanzar más allá de lo ya acordado.
- Los íconos de descarga del Paso 5 hacen `POST finalizaSolicitud.do`. No se ha confirmado qué
  registra el servidor con un CURP sin trámite previo.
- Un captcha distinto del inicial, un aviso o un bloqueo del sitio son señales de alerta: pausar.
- El captcha se envía como imagen a un tercero (personas que lo resuelven); no lleva datos personales.
  Resolverlo así evita un control anti-bot del sitio: los términos de uso específicos del portal no están revisados.
- Los términos de uso del sitio son desconocidos; la decisión de automatizar la asume el usuario.
- Los PDF descargados contienen datos personales: se guardan solo en la caché privada de la app.
