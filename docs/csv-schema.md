# Esquema del CSV (se construye con los hallazgos de las sondas)

| Pantalla | Columna CSV | Campo destino (key) | Tipo | Obligatorio | Transformación | Estado |
|---|---|---|---|---|---|---|
| paso-1 | curp | curp | texto, 18 car. | sí | mayúsculas, sin espacios | propuesto (sin aprobar) |
| paso-1 | correo | correo | texto ≤ 200 | sí | minúsculas, sin espacios | propuesto (sin aprobar) |
| paso-1 | correo (misma columna) | confirma | texto ≤ 200 | sí | = correo | propuesto (sin aprobar) |
| paso-1 | — | captcha | — | sí | NO es CSV: lo resuelve Anti-Captcha (sin clave, el humano) | decidido y verificado |

Pasos 2–4 (persona empleadora, persona trabajadora del hogar, confirmación) y el calendario de 21 días hábiles: pendientes de sondeo.
Reglas: vacío = no tocar; el mapeo lo aprueba el humano antes de ejecutarse (cap. 3.4).
