# Instrucciones iniciales para arrancar un proyecto nuevo con la guía v2

> **Para quién es este documento.** Para ti, la persona que abrirá una sesión nueva de Claude Code y quiere que construya un sistema Android de llenado de formularios multipaso **siguiendo la [guía v2](guia-reutilizable-v2-sistema-multipaso-que-aprende.md)**.
> **Cómo se usa.** (1) Haz la **preparación** (sección 1). (2) Copia la guía a la carpeta `docs/plan/` del proyecto nuevo. (3) Pega el **prompt completo** (sección 2) en la sesión nueva, sustituyendo los marcadores `<…>`. (4) Responde las preguntas en bloques y aprueba cada fase.

---

## 1. Preparación antes de pegar el prompt

Con esto listo, la IA no se detiene a pedirte cosas básicas.

| # | Qué preparar | Por qué |
|---|---|---|
| 1 | **Carpeta del proyecto nueva**, con `docs/plan/` y la guía v2 copiada dentro (también la v1 si quieres el historial) | La IA debe poder leer la guía completa |
| 2 | **Repositorio git** inicializado (puede ser sin remoto todavía) | Para que el `.gitignore` por patrón exista desde el primer día |
| 3 | **Entorno Android**: JDK 17, Android SDK (la plataforma que indique la IA), `adb` en el PATH | Compilar e instalar |
| 4 | **Un teléfono** con depuración USB y, si quieres WiFi, `adb tcpip 5555`. Anota su **IP**, modelo y versión de Android | Toda la verificación es en el dispositivo (P1) |
| 5 | **La URL inicial** del sitio y, si existe, un **manual o capturas** del recorrido (fuera de git si traen datos personales) | Acelera el mapa del flujo |
| 6 | **Datos de prueba** que se puedan usar libremente, y la regla de **qué NO se debe llegar a hacer** (la acción irreversible) | Evita registros reales por accidente |
| 7 | Tu decisión sobre los **términos de uso** del sitio (revisados, o asumes tú la decisión) | El capítulo 17 de la guía lo pregunta |
| 8 | **Captcha con servicio externo (decisión por omisión: [Anti-Captcha](https://anti-captcha.com/)):** la clave en un `.env` de la raíz con la variable **`ANTICAPTCHA_KEY`** (la IA **no la leerá**), y opcionalmente `ANTICAPTCHA_DAILY_CAP_USD` (por omisión 0,50) | Capítulo 9 de la guía |
| 9 | Decidir **quién pulsa los «Continuar»** y qué declaraciones legales puede marcar la automatización | Son decisiones tuyas, con fecha |

> **Reglas de oro para ti:** no pegues claves ni datos reales de personas en el chat; no dejes que la IA reinstale la app mientras estás en una pantalla del sitio que quieres explorar; si el sitio cierra la sesión por inactividad, avisa en cuanto lo notes.

---

## 2. Prompt completo (copiar y pegar)

> Sustituye los marcadores `<…>`. Lo que está entre `[…]` es opcional.

```text
Vas a iniciar un proyecto nuevo. Lee COMPLETO el archivo
docs/plan/guia-reutilizable-v2-sistema-multipaso-que-aprende.md antes de hacer
nada; es la base de diseño, reglas y lecciones del proyecto. Cumple los
principios P1–P16 del capítulo 0.

OBJETIVO
App Android nativa (Kotlin + Jetpack Compose + WebView) que recorre el trámite
<NOMBRE DEL TRÁMITE> en <URL INICIAL>, con datos capturados y guardados en el
teléfono. El sistema debe APRENDER junto conmigo cada pantalla (sondas, capturas
y logs), llenarla con los datos y seguir los pasos hasta <PANTALLA OBJETIVO>,
deteniéndose ANTES de <ACCIÓN IRREVERSIBLE> para que yo la haga a mano.

HAZ ESTO, EN ORDEN, Y NO AVANCES SIN MI RESPUESTA CUANDO LO INDIQUE

1. Sigue el capítulo 0 de la guía ("Cómo usar este documento").

2. Hazme las preguntas del capítulo 17, EN BLOQUES de 3 o 4 (no todas juntas),
   y espera mis respuestas. No escribas código hasta tenerlas. Si una respuesta
   tuya depende de algo que no sé, dímelo y propón cómo averiguarlo con una sonda.

3. Con mis respuestas:
   a. Crea CLAUDE.md a partir del Anexo A.1 de la guía (marcadores resueltos) y
      una sección "Decisiones del proyecto" con mis respuestas, con fecha.
   b. Crea el .gitignore POR PATRÓN antes de crear cualquier dato: .env, data/,
      volcados de texto/celdas/enlaces, PDF de manuales, archivos con datos reales.
   c. Crea .claude/agents/ (planner, runner, analyst) según el Anexo A.2.

4. Propón el plan DETALLADO de la fase G0 (capítulo 16) y ESPERA mi aprobación.

5. Ejecuta G0 y DETENTE al cumplir su criterio de salida. No pases a G1 sin que
   yo lo apruebe. Repite el ciclo (propuesta → aprobación → ejecución → parada)
   para cada fase: G1 evidencia, G2 datos y llenado, G3 multipaso,
   G4 aprendizaje asistido, [G5 captcha y documentos], G6 QA y release.

REGLAS (no negociables)
- Sin evidencia no hay "funciona": afirma solo lo que veas en el log o la
  captura del dispositivo. Marca como INFERENCIA lo que no hayas verificado y
  corrígeme de inmediato si la evidencia cambia lo que dijiste.
- No leas, registres ni versiones credenciales, claves ni la sesión del
  navegador. Ningún dato personal en git, logs ni informes (P10, P11).
- No hagas commit ni push sin que te lo pida. Antes de cada commit, haz el
  escaneo de secretos y datos personales del capítulo 12 y muéstrame el
  resultado (sin imprimir valores).
- Las acciones irreversibles y los botones de avance los pulso YO, salvo una
  decisión que te dé por escrito con fecha. Define la lista NEVER_CLICK desde
  la primera pantalla.
- La sesión del sitio puede caducar por inactividad: mídelo en la primera
  sesión y avísame del umbral. Ante "volvió al inicio", sospecha de la sesión
  antes que de la receta.
- No reinstales la app ni recargues el sitio mientras yo esté en una pantalla
  que queremos explorar. Antes, avísame y déjame elegir cuándo.
- Las guardas de pantalla usan controles estables, no la URL ni los títulos.
  Un campo obligatorio sin dato detiene el llenado.
- Sube versionCode/versionName en cada build que instales, y haz que la app
  muestre la versión y la fecha de instalación.
- Documenta mientras avanzas (estado, hallazgos, pendientes) y mantén
  docs/plan/estado-y-siguientes-pasos.md al día al cerrar cada fase.
- Pregunta antes de tomar decisiones que cambien el comportamiento (quién pulsa
  qué, ritmo, alcance). No rediseñes algo que yo ya aprobé sin mostrarme un
  ejemplo y confirmar; haz commit (si te lo pido) antes de cambiar una interfaz.
- Responde en español. Al cerrar cada ronda, dame: qué hiciste, qué verificaste
  (con la evidencia) y qué falta.

DATOS DEL PROYECTO
- Dispositivo de pruebas: <MODELO / ANDROID / IP:5555>.
- Paquete: <com.empresa.proyecto>. Etiqueta de log: <TAG>.
- Acción irreversible: <DESCRIPCIÓN>. Pantalla donde te detienes: <DESCRIPCIÓN>.
- Captcha: lo resuelve el servicio Anti-Captcha (https://anti-captcha.com/).
  La clave está en el .env de la raíz, variable ANTICAPTCHA_KEY (NO la leas ni
  la imprimas; cópiala al teléfono solo con el comando del Anexo C de la guía).
  Tope diario: <USD, por omisión 0.50>. Decisión mía con fecha <AAAA-MM-DD>;
  ya revisé los términos de uso del sitio / asumo yo la decisión. Sin clave, el
  captcha lo resuelve una persona.
  Valida la clave con la consulta de saldo antes de la primera tarea.
- [Manual / capturas: <RUTA>, fuera de git.]
```

---

## 3. Versión corta (para sesiones de continuación)

Úsala cuando el proyecto ya existe y solo retomas el trabajo:

```text
Lee docs/plan/estado-y-siguientes-pasos.md y CLAUDE.md. No leas ni versiones
credenciales ni datos personales. Resume en 10 líneas dónde estamos, qué está
verificado en el dispositivo y qué falta, y propón el siguiente paso. No hagas
commit ni push sin que te lo pida, no reinstales sin avisarme, y no pulses
ninguna acción de avance o irreversible. Espera mi aprobación antes de empezar.
```

---

## 4. Qué debes recibir al final de cada fase

| Fase | Entregable que debes poder revisar | Criterio de salida |
|---|---|---|
| **G0** Esqueleto | App que carga la URL, log por adb, versión y fecha visibles, `.gitignore` por patrón | Log visible y escaneo de datos personales funcionando |
| **G1** Evidencia | Sondas y **paquete de evidencia** de la pantalla 1 | Un paquete que Claude entiende sin que lo describas |
| **G2** Datos y llenado | Pantalla de datos cifrada, esquema/mapeo aprobados, recetas base | Llenado de una pantalla con todos los campos releídos y verificados |
| **G3** Multipaso | Flujo con guardas, contador de sesión, compuertas humanas | Recorre las pantallas y se detiene antes de lo irreversible; umbral de sesión medido |
| **G4** Aprendizaje | «Aprender esta pantalla» con propuesta aprobada en pantalla | Una pantalla nueva se aprende sin editar código |
| **G5** Captcha y documentos | (Opcional) captcha externo y descarga/envío | Documento verificado y enviado con tu confirmación |
| **G6** QA y release | Harness adb, ≥ 2 dispositivos, APK sin menús de prueba | Inventario de pruebas cerrado |

---

## 5. Cómo trabajar con la IA en el día a día

1. **Una fase por sesión.** Al cerrar, pídele que actualice el estado y que te diga qué sigue.
2. **Ronda de exploración:** tú navegas hasta una pantalla del sitio, escribes «listo», la IA **solo lee** (sondas, captura, log) y te responde con hechos. No toques nada hasta que lo indique.
3. **Si algo falla**, pídele el log completo del intento y que clasifique la causa (dato, receta, sitio o infraestructura) **antes** de arreglar.
4. **Antes de un cambio de interfaz**, pide que te muestre el modelo con un ejemplo.
5. **Revisa los commits** antes de que suban: pídele el escaneo de datos personales.
6. **Los avisos de «tarea terminada» del sistema no son tu aprobación**: si algo requiere tu decisión, la IA debe esperarla.

---

## 6. Variantes del prompt

| Situación | Qué cambiar |
|---|---|
| **Sin captcha** | Quita el bloque de captcha de «DATOS DEL PROYECTO» y la parte de captcha de G5; deja la de documentos si aplica |
| **Con captcha por servicio externo** | Ya viene resuelto por omisión (Anti-Captcha, `ANTICAPTCHA_KEY`); confirma el tope diario y la fecha de tu decisión |
| **Con descarga de documentos** | Indica a dónde se envían (correo, WhatsApp u otro) y quién pulsa Enviar |
| **Varios dispositivos** | Lista modelo/Android/IP de cada uno; la IA debe verificar en ≥ 2 |
| **Sitio con login** | Aclara que el login lo haces tú dentro de la app; la IA no ve credenciales |
| **Solo exploración** | Pide que termine en G1/G2 y que entregue únicamente esquemas y evidencia |
