# Pruebas del Parcial 3 en AWS

Ejecutadas el 24 de noviembre de 2026 contra un Learner Lab de AWS Academy (us-east-1). Configuración:
- `admin-service` y dos instancias de `streaming-service` (A en el 8083, B en el 8084);
- el backend en local (perfil `local` con H2), publicando de verdad en SNS y en Kinesis;
- una instancia de `mensajeria-service`, para comprobar que lo del Parcial 2 sigue funcionando.

Los logs completos quedaron fuera del repositorio porque incluyen el ID de la cuenta.

---

## 1. Plataforma declarada en código

`POST /admin/streams/topologia` (72 s):

| Recurso | Resultado |
|---|---|
| `pedido360-pedidos` | ACTIVE · PROVISIONED · 3 shards · 24 h · métricas por shard `IncomingRecords`, `OutgoingRecords`, `IteratorAgeMilliseconds` |
| `pedido360-actividad` | ACTIVE · PROVISIONED · 3 shards · 24 h · `IncomingRecords`, `OutgoingRecords` |
| `pedido360-fallidos` | ACTIVE · PROVISIONED · 1 shard · 24 h |
| `pedido360-streaming-estado` | Tabla DynamoDB On-Demand, con TTL en `expiraEn` |
| `pedido360-pedidos-retraso-consumo` | Alarma sobre `GetRecords.IteratorAgeMilliseconds` > 60 s, en 2 de 3 minutos, con "sin datos" como alarma |

**Hallazgo:** en el primer intento, al subir la retención de pedidos a 168 h, AWS respondió `AccessDeniedException`. El motivo es *"explicit deny in a service control policy"* sobre `kinesis:IncreaseStreamRetentionPeriod`. Desde entonces la declaración usa 24 h, y si AWS deniega un paso, lo informa como `DENEGADO` y sigue con el resto.

**Arranque de la KCL:** la primera vez, cada aplicación crea su tabla de coordinación y un índice secundario (`LeaseOwnerToLeaseKeyIndex`). DynamoDB tardó unos 10 minutos en dejar el índice `ACTIVE`; recién ahí se tomaron los leases. Las dos instancias se repartieron los 9 shards. Por ejemplo, ventas quedó con A: 000 y 002, y B: 001.

## 2. Flujo exitoso

Un cliente confirma dos pedidos (18.470 y 3.870). El pedido 1 recorre `EN_PREPARACION → LISTO → EN_REPARTO → ENTREGADO` y el pedido 2 queda en preparación. Además se envía un lote de 6 eventos de actividad.

| Qué | Evidencia |
|---|---|
| Productor | `Evento publicado en pedido360-pedidos \| idEvento=dabb4cb1-… \| tipo=PEDIDO_CREADO \| pedidoId=1 \| shard=shardId-000000000002 \| seq=4968…` |
| Lote `PutRecords` | `{"recibidos":6,"publicados":6,"rechazados":0,"intentos":1}` |
| Dos aplicaciones, mismo evento | `[pedido360-tablero-ventas] Procesado \| idEvento=dabb4cb1-…` y `[pedido360-seguimiento-cocina] Procesado \| idEvento=dabb4cb1-…`, cada una con su `Checkpoint \| shard=… \| seq=…` |
| Parcial 2 intacto | `[notificaciones]`, `[documentos]` y `[auditoria] Procesado y confirmado \| idEvento=dabb4cb1-…` (el mismo id viaja por SNS) |
| Tablero de ventas | 2 pedidos · $22.340 · 1 entregado · ticket promedio $11.170 · más vendido: Bebida Lata (3 u.) |
| Seguimiento de cocina | En curso: pedido 2 `EN_PREPARACION`; finalizado: pedido 1 `ENTREGADO` |
| Tendencias | 6 eventos; Hamburguesa Doble +2/−1; búsqueda "papas"; categoría "hamburguesas" |

## 3. Flujo fallido

Publicado con `POST /admin/streams/pruebas`:

| Variante | Resultado en cada aplicación | En `pedido360-fallidos` |
|---|---|---|
| Inválido (sin `idEvento`, total −500) | `Evento no procesable \| tipoFalla=INVALIDO \| intento=1 \| causa=Evento invalido: idEvento no debe estar vacío; total debe ser mayor que o igual a 0` | 2 registros (ventas y cocina) |
| Ilegible (no JSON) | `tipoFalla=ILEGIBLE \| intento=1 \| causa=Contenido ilegible: Unrecognized token 'pedido'…` | 2 registros |
| Duplicado (mismo `idEvento` dos veces) | Primera copia procesada; segunda: `Duplicado descartado` | — |
| Incoherente (total 1 con ítems por 14.470) | Ventas: `NO_RECUPERABLE \| causa=El total del pedido (1) no cuadra con sus items (14470)`. Cocina lo procesa | 1 registro (ventas) |
| Falla transitoria inducida en ventas | `Error transitorio, se reintenta \| intento=1 de 4 \| espera=223 ms` → `intento=2 de 4 \| espera=458 ms` → procesado en el intento 3 | — |

El visor de datos (`GET /admin/streams/pedido360-fallidos/registros`) devolvió los 5 registros, cada uno con aplicación, tipo de falla, intentos, causa, shard y número de secuencia de origen. Ningún evento defectuoso detuvo su shard: los siguientes se procesaron con normalidad.

## 4. Caída de una instancia

| Aplicación | Antes | Después de detener B |
|---|---|---|
| `pedido360-tablero-ventas` | A: 000, 002 · B: 001 | A: 000, 001, 002 (unos 35 s) |
| `pedido360-seguimiento-cocina` | A: 001 · B: 000, 002 | A: 000, 001, 002 (unos 2 min) |
| `pedido360-tendencias-menu` | A: 002 · B: 000, 001 | A: 000, 001, 002 (unos 50 s) |

A continuó desde el checkpoint que había dejado B. Por ejemplo: `Lease tomado | shard=shardId-000000000001 | continua desde checkpoint=…78302994`. El tiempo es el failover de 10 s más el ciclo de asignación de la KCL 3.

Al volver, B no recibió leases: la KCL 3 asigna según la utilización de cada instancia y, con la carga baja de la prueba, no mueve shards sin necesidad.

## 5. Cambios en vivo desde el administrador

| Operación | Resultado |
|---|---|
| `PUT /admin/streams/pedido360-actividad/shards {"shards":4}` | `UPDATING` → `ACTIVE`, con 4 shards abiertos. Los anteriores quedaron cerrados, con su padre registrado |
| `PUT /admin/streams/pedido360-pedidos/retencion {"horas":168}` | 400: denegado por la SCP del Learner Lab (esperado) |
| `PUT /admin/streams/pedido360-pedidos/shards {"shards":9}` | 400: "Kinesis solo permite entre la mitad y el doble de shards por operación" (lo valida el administrador antes de llamar) |

## 6. Supervisión

- Alarma `pedido360-pedidos-retraso-consumo`: **OK**. Motivo de AWS: *"2 out of the last 3 datapoints [0.0, 0.0] were not greater than the threshold"*.
- `GetRecords.IteratorAgeMilliseconds`: 0 ms en cada minuto (los consumidores van al día).
- `IncomingRecords` por shard de pedidos: 1 / 5 / 7. `OutgoingRecords`: 5 / 11 / 16, el doble porque dos aplicaciones leen cada registro, más el visor.
- Retraso por shard calculado por el administrador (`GET /admin/aplicaciones/pedido360-tablero-ventas`): 0 ms en los 3 shards.

## 7. Pruebas automáticas

| Servicio | Pruebas | Resultado |
|---|---|---|
| `streaming-service` | 20 | OK |
| `backend` | 3 | OK |
| `admin-service` | 2 | OK |

## 8. Capturas de evidencia (25 de noviembre de 2026)

Tomadas en una segunda corrida completa, esta vez con la cadena real del Parcial 1:
- el login de Cognito;
- API Gateway con el autorizador JWT;
- el backend en EC2 con RDS.

Desde la consola se recortó la barra superior, donde aparecen el ID de la cuenta y el usuario.

| Captura | Qué muestra |
|---|---|
| [K01](evidencias/ep3/K01-streams-lista.png) | Los 3 streams activos en modo aprovisionado |
| [K02](evidencias/ep3/K02-pedidos-resumen.png) | Resumen de `pedido360-pedidos`: activo, 1 día de retención, aprovisionado |
| [K03](evidencias/ep3/K03-pedidos-capacidad-etiquetas.png) | 3 particiones, 3 MiB/s de escritura y 6 MiB/s de lectura, etiqueta `claveParticion=pedidoId` |
| [K04](evidencias/ep3/K04-pedidos-retencion-metricas-shard.png) | Retención de 24 h y métricas mejoradas por partición |
| [K05](evidencias/ep3/K05-visor-datos-pedidos.png) | Visor de datos: eventos del pedido 1 (clave 1) en el mismo shard y en orden |
| [K06](evidencias/ep3/K06-registro-json.png) | Un registro abierto: el DTO en JSON |
| [K07](evidencias/ep3/K07-actividad-4-shards-tras-reshard.png) | `pedido360-actividad` con 4 particiones después del resharding en vivo |
| [K08](evidencias/ep3/K08-visor-fallidos.png) | Stream de fallidos con 5 registros |
| [K09](evidencias/ep3/K09-fallido-no-recuperable-json.png) | Un fallido: shard y secuencia de origen, tipo de falla, causa y contenido original |
| [D01](evidencias/ep3/D01-dynamodb-tablas.png) | Tablas de DynamoDB: 3 de coordinación (KCL) y la de estado |
| [D02](evidencias/ep3/D02-tabla-coordinacion-A-y-B.png) | Tabla de coordinación de ventas: shards repartidos entre A y B |
| [D03](evidencias/ep3/D03-tabla-coordinacion-tras-caida-B.png) | La misma tabla después de detener B: todo en A |
| [C01](evidencias/ep3/C01-alarma-sin-datos-en-alarma.png) | Alarma en ALARMA mientras nadie leía (sin datos = alarma) |
| [C02](evidencias/ep3/C02-alarma-detalle.png) | Configuración de la alarma |
| [C03](evidencias/ep3/C03-alarma-ok-historial.png) | Alarma en OK, con el historial gris → rojo → verde |
| [C04](evidencias/ep3/C04-monitoreo-pedidos.png) | Monitoreo del stream: edad del iterador en 0 |
| [A01](evidencias/ep3/A01-tablero-en-vivo.png) | Panel Streaming: el tablero se actualiza solo con los pedidos reales |
| [A02](evidencias/ep3/A02-tabla-coordinacion-panel.png) | La tabla de coordinación desde el administrador, con retraso de 0 ms por shard |
| [A03](evidencias/ep3/A03-eventos-prueba-duplicado.png) | Evento duplicado: misma clave, mismo shard |
| [A04](evidencias/ep3/A04-fallidos-panel.png) | Visor de fallidos del panel |
| [A05](evidencias/ep3/A05-reshard-en-vivo.png) | Resharding en vivo: actividad pasa a `UPDATING` |
| [L01](evidencias/ep3/L01-log-flujo-exitoso.png) | Logs: procesado y checkpoint en las dos aplicaciones |
| [L02](evidencias/ep3/L02-log-manejo-errores.png) | Logs: ilegible, inválido, no recuperable, duplicado y reintentos (222 ms → 432 ms → OK) |
| [L03](evidencias/ep3/L03-log-failover.png) | Logs: A toma los shards de B y sigue desde su checkpoint |
| [L04](evidencias/ep3/L04-log-sqs-parcial2.png) | Logs: las 3 colas SQS del Parcial 2 siguen procesando |
| [L05](evidencias/ep3/L05-log-reshard-hijos.png) | Logs: tras el resharding, la KCL toma los shards hijos |

Resultados de esta corrida:
- **Distribución por shard** (CloudWatch, `pedido360-pedidos`): entrada 2 / 3 / 9 y lectura 6 / 7 / 29. El shard 002 recibió los 5 eventos del pedido 1, que tienen la misma clave.
- **Caída de B:** A tomó todos sus shards entre 13:29:05 y 13:29:34, unos 35 a 65 s después de detener B.
