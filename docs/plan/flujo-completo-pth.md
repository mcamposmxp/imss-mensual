# Flujo completo del trámite (mapa)

Fuente: manual en PDF de `docs/` (fuera de git; 20 págs.) + sondeo del dispositivo. Sin valores personales.
Estado por paso: ✅ verificado en el dispositivo · 🔶 solo visto en el manual · ⏳ pendiente.

| # | Pantalla | Qué se hace | Estado |
|---|---|---|---|
| 1 | Iniciar trámite (`editarTrabajador.do`) | CURP y correo de la persona empleadora (x2), captcha, **Continuar** | ✅ llenado + captcha (Anti-Captcha) |
| 2 | Persona empleadora (`RegistraDomicilio.do`) | teléfono, CP + **Buscar**, colonia, calle, núm. ext., subdelegación, casilla del manifiesto, **Continuar** | ✅ llenado |
| 3 | Persona trabajadora (`SalBase.do`) ✅ llenado automático verificado el 2026-10-12 | CURP + NSS + **Consultar** (rellena nombre, apellidos, sexo, nacimiento), correo, CP + **Buscar**, colonia, calle, núm. ext., luego «Información de cotización»: mes de incorporación (lista), radio «Pago por día», salario diario (≥ el mínimo vigente indicado en pantalla), **calendario: marcar ≥ 21 días**, **Continuar** | 🔶 sondeada la parte visible; ⏳ cotización y calendario |
| 4a | Período de cobertura | revisar período y totales, **Continuar** | 🔶 |
| 4b | Confirmación de datos | casilla «He leído y acepto los términos y condiciones» (abre un texto largo con **Aceptar** al final), **Continuar**, y un diálogo «Confirmación requerida: ¿Estás seguro que toda tu información es correcta?» con **Aceptar** | 🔶 |
| 5 | Líneas de pago (`RegistraDomicilio.do`) | descargar **Comprobante** y **Línea de captura** (PDF), enviarlos | ✅ descarga y envío |

## Dónde está la acción irreversible
El manual muestra que el registro del trámite ocurre en la pantalla **4b**: aceptar los términos y el diálogo
«Confirmación requerida». Después de eso aparece el Paso 5 con la fecha límite de pago y los documentos.
Decisión del proyecto (CLAUDE.md): esa aceptación la hace el humano; las pruebas NO pasan de la selección del
calendario y la aceptación de términos (un documento final por CURP al mes).

## Datos de cotización observados (manual)
- «Mes de incorporación»: lista con los meses disponibles (cambia con la fecha; el manual muestra septiembre/octubre, el dispositivo octubre/noviembre).
- Pago por día: el sitio exige al menos 21 días marcados; el contador «Seleccione los días…» refleja cuántos hay.
- El salario diario no puede ser menor al mínimo que muestra la pantalla ni mayor a 25 UMA.

## Notas para automatizar
- Calendario: es un componente propio (no un `<input>`); se necesita sonda de sus celdas (`cells`, `links`) antes de escribir la receta.
- «Consultar» y «Buscar» son consultas AJAX: esperar a que se rellenen los campos de solo lectura y las listas.
- El manual guarda los PDF con el diálogo de archivo del navegador; en la app se capturan como blob (ya resuelto).

## Hallazgos del sondeo de 2026-10-12 (dispositivo)
- **Paso 3 (`SalBase.do`)**: «Continuar» aparece habilitado aunque falten campos obligatorios (calle, número exterior…); el sitio solo valida al pulsarlo y, si falta algo, no avanza. El aviso «habilitado» de la app NO prueba que el formulario esté completo: la app detiene el llenado si un campo obligatorio no tiene dato.
- **Calendario**: jQuery UI datepicker (3 tablas en el DOM, 1 visible). Día marcado = `td.ui-datepicker-current-day` con `a.ui-state-high`; el contador `#diasTrabajados` refleja cuántos hay. El calendario sigue al mes elegido.
- **Paso 4a (`LstPerAseg.do`, «Periodo de cobertura»)**: tabla de período de pago, días de cobertura, cuotas IMSS, Infonavit y total, con **Regresar** y **Continuar**. **Es obligatorio seleccionar el renglón del «Período de pago»** (clic en la fila; no hay radio ni casilla; la fila se resalta en rojo oscuro). Con 21 días hábiles marcados (lun–vie) la cobertura muestra esos mismos 21 días y un total de $2,474.70.
  - *Corrección:* una lectura previa mostró 25 días con sábados; no se reprodujo y se descarta (probablemente la selección del calendario era otra en ese momento).
  - *Por sondear:* la clase/atributo de la fila seleccionada (la sonda `cells` solo capturaba celdas; se amplía en la próxima versión con el `<tr>`).
- Pendiente de sondear: **4b** (Confirmación de datos y términos).

## Verificación del 2026-10-12 (v0.12.4, sesión fresca)
- **Pasos 1, 2 y 3 se llenan de forma automática y verificada** con el par Patrón → Empleado de la pantalla de datos. En el Paso 3: consulta por CURP+NSS, domicilio con «Buscar», colonia, mes, radio «Pago por día», salario y calendario (`dias marcados=21 contador=21`).
- **Período de cobertura (`LstPerAseg.do`)**: el renglón del período es `<tr id="Mensual" class="highlight">`; es obligatorio seleccionarlo antes de «Continuar». Con 21 días hábiles la cobertura muestra esos 21 días y total $2,474.70.
- **Lista de trabajadores / confirmación (`LstTrab.do`)**: tras «Continuar» del período llega una pantalla con la tabla del empleado (cobertura, domicilio, días, salario, cuotas), y los botones «Cancelar», «Continuar» y «Agregar persona trabajadora del hogar». Hay un botón oculto «Aceptar» con `id=guardaTrabajador` (por el nombre, probablemente guarda/registra: NO se automatiza). Los términos y el diálogo «Confirmación requerida» aparecen en los pasos siguientes según el manual; no se han abierto.
- **Por qué «Continuar» devolvía al Paso 1: la sesión del servidor caduca por inactividad.** Misma petición `POST /pth/LstTrab.do`: con ~15 min sin tocar el servidor (6:26→6:41) el sitio devolvió el formulario del Paso 1; con ~2,5 min (07:35:53→07:38:20) avanzó bien. El umbral exacto está entre ~3 y ~15 min y no se ha medido. Las sondas NO refrescan la sesión (solo leen la página).
- Bloqueo del CP tras «Buscar» (campo gris): hay que pulsar «Limpiar» antes de buscar de nuevo; la receta aún no lo hace solo.
- La colonia y la subdelegación deben coincidir con el texto de la lista del sitio para ese CP; si no, el llenado se detiene con `opcion_no_encontrada` y el log lista las opciones.

## Pantalla de términos (sondeada 2026-10-12, solo lectura)
Tras «Continuar» en la lista de trabajadores (`LstTrab.do`), la misma pantalla muestra:
- Casilla `#checkTerminos` «He leído y acepto los términos y condiciones» (la marca el HUMANO).
- Modal `#myModal2` «Términos y condiciones» (carta de uso de la CURP, texto largo con scroll interno) con el botón `Aceptar` `#aceptaTerminos` al final.
- `Continuar` (`#continuar`) deshabilitado hasta aceptar los términos; `Regresar` y `Cancelar` visibles.
- `Aceptar` `#guarda` oculto: por nombre y por el manual, es el de «Confirmación requerida: ¿Estás seguro que toda tu información es correcta?» (probable punto donde se concreta el registro; NO visto abierto).
- La sesión expira por inactividad (medido: funciona a ~2,5 min, caduca a ~9 min).

**Acciones que NUNCA automatiza la app:** marcar `#checkTerminos`, pulsar `#aceptaTerminos`, pulsar `#guarda`, y cualquier «Aceptar» de confirmación. Las hace una persona.
