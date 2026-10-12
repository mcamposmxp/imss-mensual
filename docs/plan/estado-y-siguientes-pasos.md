# Estado del proyecto y siguientes pasos

Última actualización: 2026-10-12 · app v0.13.0 (versionCode 25) · dispositivo de pruebas: Xiaomi 2412DPC0AG, Android 16.
Regla de lectura: ✅ verificado con el log del dispositivo · 🟡 implementado pero **sin verificar en el dispositivo** · ⏳ pendiente · ❌ descartado.

## 1. Qué hace la app hoy

App Android nativa (Kotlin + Compose + WebView) que llena el trámite «Incorporación de las personas trabajadoras del hogar» del IMSS (`adodigital.imss.gob.mx/pth`) con datos guardados en el teléfono, resuelve el captcha con un servicio y, al final, descarga los dos PDF y los envía por correo o WhatsApp. **Una persona pulsa todos los «Continuar» y confirma lo irreversible.**

| Etapa | Estado | Evidencia / nota |
|---|---|---|
| Esqueleto WebView, log `ImssForm`, versión visible | ✅ | F0 (v0.1.x) |
| Sondas DOM (general, fields, buttons, choices, dialogs, cells, links, text, fn, hook, capture, ready) | ✅ | `docs/qa/*-probe-*.json` |
| Pantalla de **Datos** (patrones y empleados, alta/edición/borrado, cifrada con Keystore) | ✅ | v0.12.4: lista, selección, persistencia tras reinstalar |
| **Paso 1** (CURP, correo, confirmación) + **captcha con Anti-Captcha** | ✅ | varias corridas; ~5–21 s; 0.0006 USD por captcha |
| **Paso 2** (teléfono, CP+Buscar, colonia, calle, números, subdelegación, manifiesto) | ✅ | 2026-10-12, sesión fresca |
| **Paso 3** (CURP+NSS+Consultar, correo, CP+Buscar, colonia, calle, números, mes, pago por día, salario, **calendario 21 días hábiles**) | ✅ | `dias marcados=21 contador=21` |
| **Período de cobertura**: seleccionar el renglón | ⏳ | el renglón es `<tr id="Mensual" class="highlight">`; hoy lo selecciona la persona; la receta de clic **no está escrita** |
| **Términos y condiciones**: marcar «He leído» y pulsar «Aceptar» de la ventana | 🟡 | `schemas/paso-4.json`, receta `acceptModal`; **v0.13.0 nunca corrió contra el sitio** |
| «Continuar» tras los términos / «Confirmación requerida» (`#guarda`) | ❌ por decisión | la app **nunca** los pulsa: tras ellos se genera el documento |
| **Paso 5**: descargar Comprobante y Línea de captura (blob → PDF) | ✅ | PDF reales (`%PDF-1.4`), 75 865 y 35 852 bytes |
| Enviar por **correo** (selector restringido a apps de correo) | ✅ envío / 🟡 selector restringido | el humano pulsa Enviar; el selector `mailto:` (v0.6.2) no se vio en pantalla |
| Enviar por **WhatsApp** (WhatsApp y Business) | ✅ | el humano elige contacto y pulsa Enviar |

## 2. Decisiones del proyecto (tomadas por el usuario)

- **Solo crear trámites nuevos** (sin actualizar ni borrar). Un documento final por CURP al mes.
- **Captcha del Paso 1**: lo resuelve Anti-Captcha (2026-10-11). Sin clave en `files/.env`, lo hace una persona. Un captcha distinto del inicial, rechazado dos veces, un aviso o un bloqueo = alerta: pausar.
- **Casilla «Manifiesto bajo protesta»** (Paso 2): la marca la automatización (2026-10-11).
- **Términos y condiciones** (Paso 4): la automatización marca «He leído y acepto» y pulsa el «Aceptar» de la ventana, y **se detiene con «Continuar» habilitado** (2026-10-12). En pruebas, la sesión termina ahí.
- **Todos los «Continuar»** los pulsa una persona. (Pregunta abierta: ¿automatizar los de los pasos 1–4a, que no registran nada? Decide el usuario.)
- Datos de prueba: pantalla de **Datos** en el teléfono (cifrada). Calendario: **primeros 21 días hábiles** (lun–vie) del mes elegido.
- Distribución: APK directo; menús de prueba ocultos en release (pendiente de implementar).

## 3. Hallazgos importantes del sitio (no obvios)

1. **La sesión del servidor caduca por inactividad**: funciona a ~2,5 min, caduca a ~9 min (no medido entre medias). Al caducar, cualquier petición devuelve el formulario del Paso 1 (aunque la URL sea otra). El recorrido completo hasta los términos debe hacerse en **menos de ~5 minutos**. Las sondas no renuevan la sesión.
2. **Reinstalar la app reinicia el sitio en el Paso 1** y pierde la pantalla. No reinstalar a mitad de una exploración.
3. **«Continuar» en el Paso 3 aparece habilitado aunque falten campos obligatorios**; el sitio valida al pulsar. La app detiene el llenado si falta un dato obligatorio (`STOP campo_requerido_vacio`).
4. **Colonia y subdelegación** deben escribirse exactamente como en la lista del sitio para ese CP (se compara sin acentos ni mayúsculas); si no, `opcion_no_encontrada` y el log muestra las opciones.
5. **Tras «Buscar» el CP queda bloqueado (gris)**: hay que pulsar «Limpiar» antes de buscar otra vez; la receta aún no lo hace sola.
6. Los textos de títulos cambian con el diseño responsivo (UA móvil/PC): las guardas de pantalla usan **controles**, no títulos.
7. Los PDF del Paso 5 son **blobs** generados por `showPDF/showLineCaptura` (`POST finalizaSolicitud.do`): se capturan dentro de la página (no desde Kotlin, sin leer cookies).
8. El captcha puede devolverse con una letra de menos: se pide `minLength=5` (los 7 vistos tenían 5 letras); aun así hay que mirarlo antes de «Continuar».
9. Las etiquetas `<label>` no están enlazadas a los campos: todos los campos se localizan hoy por `id` (`via=id`), no por texto de etiqueta.

## 4. Cómo retomar (arranque rápido)

1. Compilar: `cd android-app && export JAVA_HOME=<jdk17> && ./gradlew assembleDebug`.
2. Instalar por WiFi: `adb connect 192.168.0.202:5555` y `adb -s 192.168.0.202:5555 install -r app/build/outputs/apk/debug/app-debug.apk` (si adb «no encuentra» el dispositivo: `adb kill-server; adb connect …`).
3. Clave de Anti-Captcha: `.env` de la raíz → `adb shell "run-as com.maxipublica.imssmensual sh -c 'cat > files/.env && chmod 600 files/.env'" < .env`.
4. Datos de prueba: la pantalla **Datos** (o `data/import.csv` + `am broadcast … --es cmd import_csv`). Elegir el par Patrón → Empleado.
5. Recorrido de prueba (rápido, sin pausas): **Llenar ▾ → Paso 1** → *Continuar* → **Paso 2** → *Continuar* → **Paso 3** → *Continuar* → seleccionar el renglón del período → *Continuar* → (lista de trabajadores; *Continuar* si hace falta para ver la casilla de términos, que debe estar **sin marcar**) → **Paso 4. Términos y condiciones**. Se detiene con *Continuar* habilitado.
6. Lectura del resultado: `adb -s <IP>:5555 logcat -d -s ImssForm` (CURP, correos y claves se enmascaran; los domicilios no se registran).
7. Comandos adb de prueba (solo debug): `am broadcast -a com.maxipublica.imssmensual.TEST --es cmd <fill|probe|docs|click|goto|import_csv|par_list|par_set|captcha_balance> --es name <valor>` (argumentos con espacios o acentos entre comillas **dentro** del comando remoto).

## 5. Pendientes, por prioridad

**Siguiente sesión (lo inmediato)**
1. **Verificar `Paso 4. Términos y condiciones` en el dispositivo** (v0.13.0): casilla → ventana → scroll → Aceptar → *Continuar* habilitado. Es lo único implementado sin probar.
2. **Receta del renglón del período** (`tr#Mensual`): clic y verificar la clase `highlight`; añadir esquema `paso-4a`.
3. **Pulsar «Limpiar» automáticamente** si el CP está bloqueado (`reset_if_locked`) en pasos 2 y 3, para poder repetir el llenado sobre la misma pantalla.
4. **Contador de tiempo de sesión** visible en la app (límite medido: entre 2,5 y 9 min).

**Después**
5. Decidir si la app pulsa los «Continuar» de los pasos 1–4a (acortaría el recorrido; los pulsa una persona hoy).
6. Medir la tasa de acierto del captcha (registrar cada intento y si el sitio lo aceptó); implementar `reportIncorrectImageCaptcha` (solo válido ≤ 60 s tras resolver).
7. Localizar campos por **texto de etiqueta** (hoy por `id`) o documentar por qué no se puede.
8. Aclarar qué registra `POST finalizaSolicitud.do` al pulsar los íconos del Paso 5 con un CURP sin trámite previo.
9. Formulario de datos: mostrar la lista de colonias del CP consultándola al sitio; evitar duplicados al importar.
10. Flujo «Pago mes completo» (sin calendario) y «Agregar persona trabajadora» (varios empleados): sin soporte ni sondeo.
11. **Release**: ocultar menús de prueba (Sondas, receptor adb), firma del APK, confirmar el nombre del paquete `com.maxipublica.imssmensual`.
12. Verificaciones visuales pendientes: ícono del launcher, barra superior desplazable con el dedo, selector de correo restringido (v0.6.2).
13. QA formal (F4/F5 del documento guía): `TestHarness`, `run_case.sh`, oráculo en la app, matrices, ≥ 2 dispositivos/versiones de Android; pruebas unitarias (CSV, validaciones). Los agentes `qa-*` existen pero no se han usado.
14. Revisar los **términos de uso del portal del IMSS** (los generales de gob.mx no mencionan automatización ni captchas; los del portal no se han revisado).

## 6. Datos privados (fuera de git)

`data/` (incluye `PRIVADO.md`, `import.csv`, `config.json`), `.env` (clave de Anti-Captcha, permisos 600), el PDF del manual y `docs/qa/Datos pruebas.md`, y los volcados con texto personal (`docs/qa/*-TEXT.json`, `*-CHOICES.json`, `*-CELLS.json`, `*-LINKS.json`). En el teléfono: `files/datos.enc` (cifrado), `files/.env`, `files/config.json`, `files/captcha_spend.json`.

## 7. Mapa de documentos

`README.md` (uso) · `CLAUDE.md` (reglas y decisiones) · `docs/plan/flujo-completo-pth.md` (mapa del flujo y hallazgos) · `docs/csv-schema.md` (columnas por paso) · `schemas/` y `mappings/` (pasos 1–4) · `docs/plan/guia-reutilizable-v2-sistema-multipaso-que-aprende.md` (**guía v2**, vigente) · `docs/plan/prompt-inicial-nuevo-proyecto-v2.md` (**instrucciones iniciales** para arrancar un proyecto nuevo con la guía) y `docs/plan/guia-reutilizable-automatizacion-formularios-webview.md` (v1, historial) · `memory.md` (lecciones operativas).

## 8. Historial resumido

F0 esqueleto → F1 sondas → Paso 5 (descarga + correo/WhatsApp) → captcha con servicio → Paso 2 → pantalla de datos cifrada → Paso 3 → sesión caduca (descubierto y explicado) → términos mapeados y receta de aceptación (sin verificar).
