# IMSS Mensual

App Android nativa (Kotlin + Jetpack Compose + WebView) que automatiza el llenado del trámite
«Incorporación de las personas trabajadoras del hogar» del IMSS
(`https://adodigital.imss.gob.mx/pth/editarTrabajador.do`) a partir de un CSV, y que al final
descarga el **Comprobante** y la **Línea de captura** y los envía por **correo** o **WhatsApp**.

> Estado: en desarrollo (v0.10.0). Probado en un Xiaomi 2412DPC0AG con Android 16.
> Lo implementado y verificado: Pasos 1 y 2 (con captcha resuelto por servicio), captura de los PDF del
> Paso 5 y envío por correo y WhatsApp. **Pasos 3 y 4 y el calendario de 21 días hábiles
> aún no están hechos.** Detalle y evidencia en
> [docs/plan/estado-y-siguientes-pasos.md](docs/plan/estado-y-siguientes-pasos.md).

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

La guía completa de diseño y lecciones está en
[docs/plan/guia-reutilizable-automatizacion-formularios-webview.md](docs/plan/guia-reutilizable-automatizacion-formularios-webview.md);
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
| `data/input.csv` | Una fila por trámite; columnas de todos los pasos: `curp,correo,tel_celular,correo_alterno,cp,colonia,calle,num_ext,num_int,subdelegacion` (ver `docs/csv-schema.md`). |
| `data/config.json` | `{"mail_to": "<correo destino>"}` |
| `.env` (raíz) | `ANTICAPTCHA_KEY=<clave>` y opcional `ANTICAPTCHA_DAILY_CAP_USD` (0.50 por defecto). Se copia a `files/.env` con `chmod 600`. |

## Uso

1. Abre la app: carga el Paso 1 del sitio.
2. **Llenar ▾ → Paso 1**: llena CURP, correo y confirmación, resuelve el captcha y verifica cada valor releyéndolo.
   **Llenar ▾ → Paso 2. Persona empleadora**: teléfono, CP + «Buscar», colonia, calle, números, subdelegación y la casilla del manifiesto.
3. El captcha se resuelve solo (Anti-Captcha: ~5 s y ~0.0006 USD en la prueba); una persona lo revisa y pulsa **Continuar**.
   `captcha_balance` por adb valida la clave.
4. En el Paso 5: **Llenar ▾ → Paso 5. Líneas de pago ▸ → Enviar por correo / Enviar por WhatsApp**.
   La app captura los dos PDF (todo o nada) y abre el correo o WhatsApp con los adjuntos; la
   persona elige contacto, revisa y pulsa Enviar.
5. El menú **Sondas** vuelca la estructura del DOM de la pantalla actual (solo lectura).

También se puede disparar por adb (solo en debug):
`adb shell "am broadcast -a com.maxipublica.imssmensual.TEST --es cmd <fill|probe|docs|click|goto|captcha_balance> --es name <valor>"`.
Los argumentos con espacios o acentos van entre comillas **dentro** del comando remoto.

## Estructura

| Ruta | Contenido |
|---|---|
| `android-app/` | Proyecto Gradle (paquete `com.maxipublica.imssmensual`). |
| `schemas/`, `mappings/` | Esquema de campos y mapeo CSV→campo por pantalla (se empaquetan como assets). |
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
