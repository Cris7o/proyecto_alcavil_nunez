# Informe de pruebas — Evaluación Parcial 2

**Pedido360** · DSY1107 Desarrollo Cloud Native I · Duoc UC
Cristóbal Alcavil · Cristóbal Núñez

Este informe reúne las pruebas de la capa de mensajería asíncrona (SNS + SQS) y las relaciona con cada indicador de la pauta. Cada prueba indica cómo se ejecuta, qué se espera y dónde queda la evidencia.

> Pruebas ejecutadas el 21 de octubre de 2026 contra AWS Academy Learner Lab (us-east-1). Las capturas de la consola y las imágenes de los logs están en `docs/evidencias/`; las imágenes de logs son extractos de los logs reales, con los nombres de clase abreviados. P07 (pedido real como cliente) y la alarma en estado ALARM (P15) quedan para la demostración en vivo.

---

## 1. Resumen

| # | Prueba | Indicador de la pauta | Resultado |
|---|---|---|---|
| P01 | Pruebas unitarias del procesador de eventos | Confirmación y manejo de errores | ✔ |
| P02 | Compilación de los cuatro proyectos | El código compila | ✔ |
| P03 | Creación de la topología en AWS | Topología declarada en configuración | ✔ |
| P04 | Parámetros de colas, DLQ y suscripciones en la consola | Muestra en AWS tópico, colas y suscripciones | ✔ |
| P05 | Fanout | Dos patrones de enrutamiento | ✔ |
| P06 | Entrega selectiva con filtros | Dos patrones de enrutamiento | ✔ |
| P07 | Flujo completo: frontend → Cognito → API Gateway → backend → SNS | Integra microservicios con las colas | ☐ |
| P08 | Mensaje duplicado | Tolerancia a duplicados | ✔ |
| P09 | Error recuperable con reintentos hasta la DLQ | Cada cola tiene DLQ · logs id, intento y causa | ✔ |
| P10 | Error no recuperable directo a la DLQ | Rutas de error diferenciadas | ✔ |
| P11 | Dos instancias sobre la misma cola | Disponibilidad | ✔ |
| P12 | Pausar y reanudar un consumidor sin perder mensajes | Microservicio administrador | ✔ |
| P13 | Crear y eliminar colas y tópicos desde la API | Microservicio administrador | ✔ |
| P14 | Reprocesar y purgar la DLQ | Microservicio administrador | ✔ |
| P15 | Alarma de CloudWatch y métricas | Métricas de monitoreo y alarma | ☐ |
| P16 | Políticas de retención y limpieza | Retención y limpieza | ✔ |
| P17 | Sin credenciales en el repositorio | Aspectos formales | ✔ |

---

## 2. Entorno de prueba

| Pieza | Dónde corre | Puerto |
|---|---|---|
| `backend` (productor) | EC2, detrás de API Gateway | 8080 |
| `admin-service` | equipo local | 8080 |
| `mensajeria-service` instancia A | equipo local | 8081 |
| `mensajeria-service` instancia B | equipo local | 8082 |
| `frontend` | local (`localhost:4173`) y Netlify | 4173 |
| SNS, SQS, CloudWatch, Cognito, RDS | AWS Academy Learner Lab, `us-east-1` | — |

Requisitos: JDK 17, Node 20, credenciales del Learner Lab en `%USERPROFILE%\.aws\credentials`, rol `LabInstanceProfile` asociado a la EC2. Pasos completos en [`PUESTA-EN-MARCHA.md`](PUESTA-EN-MARCHA.md).

---

## 3. Pruebas automatizadas

### P01 — Pruebas unitarias del procesador de eventos

`ProcesadorEventos` concentra la política de confirmación de los tres consumidores, así que se prueba aislado, sin AWS.

```bash
cd mensajeria-service && ./mvnw test
```

| Caso (`ProcesadorEventosTest`) | Qué verifica |
|---|---|
| Un evento procesado sin errores se confirma una sola vez | ruta de éxito, ACK explícito |
| Un evento repetido se confirma sin volver a ejecutar la acción | tolerancia a duplicados |
| Un error no recuperable va a la DLQ en el primer intento | no gasta reintentos inútiles |
| Un error recuperable con intentos disponibles no se confirma | SQS lo reentrega al vencer el visibility timeout |
| Un error recuperable con los reintentos agotados va a la DLQ con la causa | derivación con id, intento y causa |
| Un cuerpo ilegible va a la DLQ sin reintentarse | mensaje mal formado |
| Un evento sin idEvento ni tipo va a la DLQ | no se puede deduplicar ni enrutar |

**Esperado:** `Tests run: 8, Failures: 0, Errors: 0` (7 originales + la del fanout).
**Evidencia:** `evidencias/P01-tests.png`
**Resultado:** ✔ 21-10-2026

### P02 — Compilación

```
scripts\construir.bat
```

**Esperado:** `BUILD SUCCESS` en `backend`, `mensajeria-service` y `admin-service`, y build de Vite sin errores en `frontend`.
**Evidencia:** `evidencias/P02-build.png`
**Resultado:** ✔ 21-10-2026

---

## 4. Pruebas contra AWS

### P03 — Creación de la topología

**Pasos:** levantar `admin-service` (lo hace `scripts\presentacion.bat`). También se puede forzar con `POST http://localhost:8080/admin/topologia`.

**Esperado en el log del administrador:**

```
Topico 'pedido360-eventos-pedido' creado o ya existente
Cola 'pedido360-notificaciones' creada o actualizada | dlq=pedido360-notificaciones-dlq
Suscripcion ... -> cola 'pedido360-auditoria' | patron=fanout
Topologia aplicada: topico 'pedido360-eventos-pedido' con 3 colas
```

Ningún nombre de cola o tópico está escrito en el código Java: todos salen del `application.yml` de cada servicio.

**Evidencia:** `evidencias/P03-topologia-log.png`
**Resultado:** ✔ 21-10-2026

### P04 — Parámetros en la consola

**Pasos:** abrir SQS, SNS y CloudWatch en la consola de AWS.

| Recurso | Esperado |
|---|---|
| SQS | 6 colas: 3 principales + 3 DLQ |
| `pedido360-notificaciones` | retención 4 días · visibility 30 s · espera 20 s · Redrive a su DLQ, maxReceiveCount 5 |
| `pedido360-documentos` | retención 14 días · visibility 60 s · maxReceiveCount 8 |
| `pedido360-auditoria` | retención 7 días · visibility 30 s · maxReceiveCount 5 |
| Las 3 DLQ | retención 14 días |
| SNS `pedido360-eventos-pedido` | 3 suscripciones SQS, raw message delivery activado |
| Filtros | notificaciones y documentos con filter policy sobre `tipoEvento`; auditoría sin filtro |
| Access policy de cada cola | permite `sqs:SendMessage` a `sns.amazonaws.com` desde el tópico |

**Evidencia:** `evidencias/P04-sqs-colas.png`, `P04-sqs-notificaciones-detalle.png`, `P04-sqs-redrive.png`, `P04-sns-suscripciones.png`, `P04-sns-filtro.png`
**Resultado:** ✔ 21-10-2026

### P05 — Fanout

**Pasos:** panel *Mensajería* (usuario admin) → botón **Pedido creado**.
**Esperado:** el evento `PEDIDO_CREADO` llega a las tres colas; aparecen tres líneas *procesado* (notificaciones, documentos, auditoría).
**Evidencia:** `evidencias/P05-fanout.png`
**Resultado:** ✔ 21-10-2026

### P06 — Entrega selectiva

**Pasos:** botón **En preparación**.
**Esperado:** aparece **solo** una línea, de auditoría. SNS descarta el evento para notificaciones y documentos antes de que toque esas colas.

| Evento | notificaciones | documentos | auditoría |
|---|:---:|:---:|:---:|
| `PEDIDO_CREADO` | ✔ | ✔ | ✔ |
| `PEDIDO_EN_PREPARACION` | — | — | ✔ |
| `PEDIDO_LISTO` | ✔ | — | ✔ |
| `PEDIDO_EN_REPARTO` | — | — | ✔ |
| `PEDIDO_ENTREGADO` | ✔ | ✔ | ✔ |
| `PEDIDO_CANCELADO` | ✔ | — | ✔ |

**Evidencia:** `evidencias/P06-filtro.png`
**Resultado:** ✔ 21-10-2026

### P07 — Flujo completo

**Pasos:**
1. Entrar como cliente (login con Cognito).
2. Agregar un producto y confirmar el pedido.
3. Entrar como admin → pestaña *Mensajería*.

**Esperado:** en el log del backend en EC2, `Evento publicado: idEvento=... tipo=PEDIDO_CREADO pedidoId=<n>`; en el panel, el evento de ese `pedidoId` procesado en las tres colas.
**Evidencia:** `evidencias/P07-pedido-cliente.png`, `P07-log-backend.png`, `P07-panel-admin.png`
**Resultado:** ☐

### P08 — Duplicado

**Pasos:** botón **Repetir el último** (republica el mismo `idEvento`).
**Esperado:** `Duplicado descartado | idEvento=... | intento=1` y confirmación sin repetir la acción.
**Limitación conocida:** el registro de eventos procesados es en memoria de cada instancia. En producción iría en una tabla compartida con `idEvento` como clave única.
**Evidencia:** `evidencias/P08-duplicado.png`
**Resultado:** ✔ 21-10-2026

### P09 — Error recuperable hasta la DLQ

**Pasos:** en *Notificaciones* elegir **Falla recuperable** → **Pedido creado**. Esperar unos 90 s.
**Esperado:**

```
Error recuperable, se reintentara | idEvento=... | intento=1 de 3 | causa=...
Error recuperable, se reintentara | idEvento=... | intento=2 de 3 | causa=...
Mensaje no entregado -> DLQ pedido360-notificaciones-dlq | idEvento=... | intento=3 | causa=Reintentos agotados (3 de 3): ...
```

Entre intentos pasan 30 s (visibility timeout). En **Ver DLQ** el mensaje aparece con id de evento, intento y causa, los mismos datos del log.
**Evidencia:** `evidencias/P09-logs-reintentos.png`, `P09-dlq-mensaje.png`
**Resultado:** ✔ 21-10-2026

### P10 — Error no recuperable

**Pasos:** **Falla permanente** → **Pedido creado**.
**Esperado:** va a la DLQ en el intento 1, sin reintentos, con causa `No recuperable: ...`.
**Evidencia:** `evidencias/P10-no-recuperable.png`
**Resultado:** ✔ 21-10-2026

### P11 — Dos instancias sobre la misma cola

**Pasos:**
1. Con A (8081) y B (8082) activas, apretar **Pedido creado** 8 veces seguidas.
2. Cerrar la ventana de la instancia B.
3. Apretar **Pedido creado** 3 veces más.

**Esperado:** en el paso 1 aparecen líneas `[A]` y `[B]` mezcladas (SQS reparte). En el paso 3 todo lo procesa `[A]`, sin mensajes perdidos: lo que B no alcanzó a confirmar vuelve a la cola al vencer el visibility timeout.
**Evidencia:** `evidencias/P11-reparto.png`, `P11-caida-B.png`
**Resultado:** ✔ 21-10-2026

### P12 — Pausar y reanudar

**Pasos:** *Notificaciones* → **Pausar** → **Pedido creado** ×3 → **Reanudar**.
**Esperado:** con el consumidor pausado, *En cola* de notificaciones sube a 3 y se mantiene; las otras dos colas sí procesan. Al reanudar, los 3 se procesan y el contador vuelve a 0.
Equivale a `POST /admin/consumidores/notificaciones/pausar` y `/reanudar`.
**Evidencia:** `evidencias/P12-pausado.png`, `P12-reanudado.png`
**Resultado:** ✔ 21-10-2026

### P13 — Crear y eliminar colas y tópicos

```bash
curl -X POST http://localhost:8080/admin/colas -H "Content-Type: application/json" \
  -d '{"nombre":"pedido360-demo","retencionSegundos":3600,"dlqNombre":"pedido360-demo-dlq","maxReceiveCount":3}'
curl http://localhost:8080/admin/colas/pedido360-demo
curl -X DELETE http://localhost:8080/admin/colas/pedido360-demo
curl -X DELETE http://localhost:8080/admin/colas/pedido360-demo-dlq

curl -X POST http://localhost:8080/admin/topicos -H "Content-Type: application/json" -d '{"nombre":"pedido360-demo-topico"}'
curl -X DELETE http://localhost:8080/admin/topicos/pedido360-demo-topico
```

**Esperado:** la cola y su DLQ aparecen en la consola de SQS con la Redrive Policy asociada, y desaparecen al eliminarlas. Lo mismo con el tópico en SNS.
**Evidencia:** `evidencias/P13-crear-cola.png`, `P13-consola-cola.png`, `P13-eliminar.png`
**Resultado:** ✔ 21-10-2026

### P14 — Reprocesar y purgar la DLQ

**Pasos:** con el mensaje de P09 en la DLQ, poner el selector en **Sin falla** → **Reprocesar** (`POST /admin/dlq/notificaciones/reproceso`). Repetir P10 y luego **Purgar** (`POST /admin/dlq/notificaciones/purga`).
**Esperado:** al reprocesar, el mensaje vuelve a su cola, se procesa bien y la DLQ queda en 0. Al purgar, la DLQ queda vacía.
**Evidencia:** `evidencias/P14-reproceso.png`, `P14-purga.png`
**Resultado:** ✔ 21-10-2026

### P15 — CloudWatch

**Pasos:** CloudWatch → *Alarms*, con un mensaje en la DLQ (esperar hasta 5 min).

| Alarma | Configuración esperada |
|---|---|
| `pedido360-<cola>-dlq-con-mensajes` (×3) | `ApproximateNumberOfMessagesVisible` de la DLQ · Maximum · 5 min · ≥ 1 · datos faltantes = OK |

| Métrica | Decisión si se dispara |
|---|---|
| Mensajes disponibles | sube y no baja → faltan consumidores o están detenidos |
| Mensajes en proceso | se queda alto → los consumidores fallan sin confirmar |
| Antigüedad del mensaje más antiguo | crece → cuello de botella, atraso real |
| Mensajes en DLQ | > 0 → revisar la causa antes de reprocesar |

**Evidencia:** `evidencias/P15-alarma-in-alarm.png`, `P15-metricas.png` (o `GET /admin/monitoreo/metricas`)
**Resultado:** ☐

### P16 — Retención y limpieza

| Cola | Retención | Justificación |
|---|---|---|
| notificaciones | 4 días | el valor de un aviso caduca |
| documentos | 14 días | la boleta se debe emitir aunque haya pasado el incidente |
| auditoría | 7 días | cubre reclamos de la semana |
| las 3 DLQ | 14 días | máximo de SQS, margen para diagnosticar |

Tres frenos al crecimiento: retención (SQS borra lo vencido), Redrive Policy (nada se reintenta para siempre) y purga manual desde el administrador. La DLQ no se purga automáticamente para no borrar la evidencia antes de revisarla.
**Evidencia:** captura de P04 (pestaña de detalles de cada cola)
**Resultado:** ✔ 21-10-2026

### P17 — Sin credenciales en el repositorio

```bash
git grep -nE "AKIA|ASIA[A-Z0-9]{16}|aws_secret_access_key\s*=\s*[^.]"
```

**Esperado:** sin resultados. Las credenciales se resuelven con `DefaultCredentialsProvider` (variables de entorno, `~/.aws/credentials` o el rol de la EC2).
**Resultado:** ✔ 21-10-2026

---

## 5. Observaciones

Completar al ejecutar las pruebas: fallas encontradas, cómo se corrigieron y el commit de la corrección.

| Fecha | Prueba | Observación | Corrección |
|---|---|---|---|
| 21-10-2026 | P11 | En la primera ejecución aparecieron eventos nuevos marcados como "Duplicado descartado": el registro de duplicados usaba solo el idEvento y el fanout entrega el mismo idEvento a las tres colas, así que la segunda cola procesada por la misma instancia lo descartaba. | La clave pasa a ser cola + idEvento (`RegistroEventosProcesados`), con una prueba unitaria nueva. Se repitieron P05–P14 con el arreglo. |
