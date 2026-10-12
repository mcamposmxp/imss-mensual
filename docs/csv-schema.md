# Esquema del CSV (se construye con los hallazgos de las sondas)

| Pantalla | Columna CSV | Campo destino (key) | Tipo | Obligatorio | Transformación | Estado |
|---|---|---|---|---|---|---|
| paso-1 | curp | curp | texto, 18 car. | sí | mayúsculas, sin espacios | propuesto (sin aprobar) |
| paso-1 | correo | correo | texto ≤ 200 | sí | minúsculas, sin espacios | propuesto (sin aprobar) |
| paso-1 | correo (misma columna) | confirma | texto ≤ 200 | sí | = correo | propuesto (sin aprobar) |
| paso-1 | — | captcha | — | sí | NO es CSV: lo resuelve Anti-Captcha (sin clave, el humano) | decidido y verificado |

Pasos 2–4 (persona empleadora, persona trabajadora del hogar, confirmación) y el calendario de 21 días hábiles: pendientes de sondeo.
Reglas: vacío = no tocar; el mapeo lo aprueba el humano antes de ejecutarse (cap. 3.4).

## Paso 2 · Persona empleadora (aprobado y VERIFICADO el 2026-10-11, v0.10.0)
El CSV es único para todos los pasos (`data/input.csv`, una fila); cada paso usa sus columnas.
Los datos de RENAPO se muestran solo para verificación: no se llenan.

| Columna CSV | Campo | Tipo | Oblig. | Transformación | Notas |
|---|---|---|---|---|---|
| tel_celular | Teléfono celular | texto, 10 dígitos | sí | solo dígitos | |
| correo_alterno | Correo electrónico alternativo | texto ≤ 200 | no | minúsculas | vacío = no tocar |
| cp | Código postal | texto, 5 dígitos | sí | solo dígitos | dispara «Buscar» y llena estado/municipio/colonias |
| colonia | Selecciona tu colonia | lista nativa | sí | texto exacto de la opción | las opciones solo existen tras Buscar |
| calle | Calle | texto ≤ 50 | sí | recortar espacios | |
| num_ext | Número exterior | texto ≤ 10 | sí | | |
| num_int | Número interior | texto ≤ 10 | no | | vacío = no tocar |
| subdelegacion | Subdelegación | lista nativa | sí | texto exacto de la opción | sus opciones cargan al elegir la colonia (verificado) |
| (ninguna) | Manifiesto bajo protesta | casilla | sí | NO es CSV | decisión del usuario (2026-10-11): la marca la automatización y el log lo registra |

## Paso 3 · Persona trabajadora (aprobado y VERIFICADO el 2026-10-12, v0.12.4)
Columnas del empleado (pantalla **Datos**): `curp_trab, nss, correo_trab, cp_trab, colonia_trab, calle_trab, num_ext_trab, num_int_trab, mes (actual|siguiente), tipo_pago (dia|mes), salario_diario, dias`.
`mes_nombre` se calcula en la app (nombre del mes en español). Los datos de RENAPO se rellenan solos tras «Consultar».
`dias` solo aplica con `tipo_pago=dia`: marca los primeros N días hábiles (lun–vie) del mes elegido (mínimo 21); verifica con el contador `#diasTrabajados`.
Colonia y subdelegación: texto exacto de la lista del sitio para ese CP.

## Paso 4 · Términos y condiciones (receta implementada v0.13.0, SIN verificar)
Sin columnas de datos. La automatización marca `#checkTerminos`, pulsa `#aceptaTerminos` y se detiene con «Continuar» habilitado (decisión del usuario, 2026-10-12).
