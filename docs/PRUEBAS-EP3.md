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
