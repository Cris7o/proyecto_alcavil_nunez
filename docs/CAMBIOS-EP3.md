# Qué cambió en el Parcial 3 (y por qué)

Este documento explica, en lenguaje directo, qué se agregó a Pedido360 para la Evaluación Parcial 3, por qué se hizo así y qué hace cada pieza. Sirve para estudiar la defensa.

---

## 1. La idea en una frase

En el Parcial 2 el sistema **avisaba** que un pedido cambió (SNS → SQS: cada aviso lo toma un consumidor y desaparece de la cola). En el Parcial 3 el sistema además **registra el historial de lo que pasa** en un *stream* de Kinesis: un registro ordenado y retenido que varias aplicaciones leen, cada una a su propio ritmo, y que se puede releer.

| | Parcial 2: SNS + SQS | Parcial 3: Kinesis Data Streams |
|---|---|---|
| Modelo | Cola de mensajes: se reparte el trabajo | Registro (log) de eventos: se comparte la historia |
| Qué pasa al consumir | El mensaje **se borra** | El evento **se queda** hasta que vence la retención; el consumidor solo avanza su *checkpoint* |
| Varios lectores | Una cola por consumidor (fanout de SNS) | Varias **aplicaciones consumidoras** sobre el mismo stream, cada una con su avance |
| Orden | No garantizado (cola estándar) | Garantizado **por clave de partición** dentro de un shard |
| Releer | No | Sí, mientras dure la retención |
| Uso típico | Tareas: enviar un correo, generar una boleta | Analítica, tableros en vivo, auditoría, reprocesar |

**Lo del Parcial 2 no se tocó**: el backend sigue publicando en SNS y las tres colas siguen funcionando igual. Kinesis se agrega *al lado*.

---

## 2. Qué se agregó

### 2.1 Tres streams (`admin-service/src/main/resources/application.yml`)

| Stream | Shards | Retención | Clave de partición | Qué lleva |
|---|---|---|---|---|
| `pedido360-pedidos` | 3 | 24 h | `pedidoId` | Cada cambio de estado de un pedido |
| `pedido360-actividad` | 3 | 24 h | `sesionId` | Lo que hace el cliente en el menú: agregar o quitar del carrito, buscar, filtrar |
| `pedido360-fallidos` | 1 | 24 h | `aplicacion` | Los eventos que una aplicación no pudo procesar, con la causa (*dead letter stream*) |

**Por qué 3 shards.** Cada shard aguanta 1 MB/s o 1.000 registros/s de escritura y 2 MB/s de lectura. Los pedidos no llegan a eso, pero 3 shards permiten que **hasta 3 instancias** de una aplicación procesen en paralelo y que, si una se cae, las otras sigan. La actividad es el flujo más voluminoso (cada clic es un evento) y llega en ráfagas a la hora de almuerzo. Fallidos tiene 1 shard porque debería recibir poco.

**Por qué esas claves de partición.** Kinesis manda todos los registros con la misma clave al mismo shard, y ahí quedan **en orden**.
- `pedidoId`: los estados de un pedido (creado → en preparación → listo → entregado) se leen en el orden correcto. Como hay muchos pedidos distintos, la carga se reparte pareja.
- `sesionId`: ordena los clics de una visita y, por tener muchísimos valores distintos, evita un *shard caliente*. Si la clave fuera `productoId`, la hamburguesa más popular saturaría un solo shard.

**Por qué 24 h de retención.** En una cuenta normal, `pedidos` y `fallidos` tendrían 7 días para poder reprocesar la semana o investigar un fallo con calma. **El Learner Lab lo prohíbe**: una política de la organización (SCP) deniega `kinesis:IncreaseStreamRetentionPeriod`. Se comprobó al desplegar y el mensaje de AWS lo dice explícitamente. Por eso todos quedan en 24 h, el mínimo, que igual cubre la jornada del local. El administrador sí implementa el cambio de retención, y en una cuenta sin esa política funciona.

**Por qué modo aprovisionado (PROVISIONED) y no On-Demand.** On-Demand no deja fijar la cantidad de shards (la decide AWS) y cobra por GB además de por hora. Con carga conocida, el aprovisionado es más barato y predecible, y la evaluación pide 3 shards fijos.

**Qué administra AWS y qué administramos nosotros.**
- **AWS**: los servidores, la **replicación de cada registro en 3 zonas de disponibilidad** antes de confirmar la escritura y el reemplazo de hardware. No hay brokers ni docker-compose.
- **Nosotros**: cuántos shards, la retención, la clave de partición, las aplicaciones consumidoras y sus tablas de coordinación.

### 2.2 El productor en el backend (`backend/.../streaming/`)

- `PedidoService` ahora publica cada cambio de estado **dos veces**: en SNS, como antes, y en Kinesis (`EventoPedidoStream`). Ambos llevan el **mismo `idEvento`**, así se puede seguir un pedido en los logs de los dos sistemas.
- `KinesisPublicadorPedidos` hace un `PutRecord` por evento y registra en el log el shard y el número de secuencia que Kinesis asignó (se verifica el resultado).
- Nuevo endpoint `POST /api/actividad`: el navegador junta los clics del cliente y los envía en lote. `KinesisPublicadorActividad` los publica con `PutRecords`, que **no es atómico**: puede aceptar la llamada y rechazar registros sueltos (por ejemplo, si un shard se satura). Por eso se revisa cada resultado y **se reenvían solo los rechazados**, con espera creciente.
- El evento de Kinesis **no lleva nombre ni correo del cliente**. El stream lo leen varias aplicaciones y se retiene, así que se minimizan los datos personales.
- Toda la configuración (región, nombres de streams, reintentos, timeouts) está en `application.properties`. En el perfil `local` Kinesis se apaga y los eventos van al log.

### 2.3 Las aplicaciones consumidoras: microservicio nuevo `streaming-service`

Usa la **Kinesis Client Library (KCL 3)**: un procesador por shard, con checkpoint y coordinación en DynamoDB. Tiene 3 aplicaciones consumidoras (*consumer groups*), cada una en su propio dominio:

| Aplicación | Stream | Dominio | Qué hace |
|---|---|---|---|
| `pedido360-tablero-ventas` | pedidos | `ventas/` | Ventas del día, ticket promedio, ventas por hora y productos más vendidos |
| `pedido360-seguimiento-cocina` | pedidos | `cocina/` | Cuánto tarda cada pedido en cada etapa y cuáles están atrasados |
| `pedido360-tendencias-menu` | actividad | `tendencias/` | Productos más agregados al carrito, búsquedas y categorías de la última hora |

**Dos aplicaciones sobre el mismo stream** (`tablero-ventas` y `seguimiento-cocina`). Cada una tiene su **propia tabla de coordinación** en DynamoDB (con su mismo nombre) y por lo tanto su **propio checkpoint**. Las dos leen *todos* los eventos de pedidos; si una se atrasa o se cae, la otra sigue al día.

**Varias instancias de cada aplicación.** Se levantan dos procesos de `streaming-service` (A en el 8083 y B en el 8084). La KCL **reparte los shards** entre ellas, por ejemplo 2 para A y 1 para B. Si se detiene una, la otra **toma sus shards** al vencer el *failover* (10 s), y continúa desde el último checkpoint.

**La tabla de coordinación** tiene una fila (*lease*) por shard:
- `leaseKey`: el shard.
- `leaseOwner`: la instancia que lo está leyendo.
- `checkpoint`: el número de secuencia del último registro confirmado.
- `leaseCounter`: sube cada vez que el dueño renueva el lease; si deja de subir, esa instancia murió.

**Separación de responsabilidades.**
- El paquete `kcl/` es **infraestructura**: lee de Kinesis, valida, reintenta, hace checkpoint y deriva a fallidos.
- Los paquetes `ventas/`, `cocina/` y `tendencias/` son **negocio puro**. Reciben un DTO ya validado y devuelven qué cambiar; no conocen Kinesis ni DynamoDB, por eso se prueban con tests simples.

**Dónde queda el resultado.** En la tabla DynamoDB `pedido360-streaming-estado`, compartida por las instancias. No puede vivir en memoria, porque cada instancia solo ve sus shards.

### 2.4 Manejo de errores (`streaming-service/.../kcl/ProcesadorRegistros.java`)

Cada registro termina en una ruta definida, y **ninguna detiene el shard**:

| Situación | Qué pasa | Va a fallidos como |
|---|---|---|
| No es JSON | No se reintenta (no se va a arreglar solo) | `ILEGIBLE` |
| JSON que no cumple el DTO (sin `idEvento`, total negativo…) | No se reintenta; la causa lista los campos malos | `INVALIDO` |
| Regla de negocio (el total no cuadra con los ítems) | No se reintenta | `NO_RECUPERABLE` |
| Error transitorio (DynamoDB ocupado, conflicto, red) | Se reintenta con **espera creciente**: 200 ms, 400 ms, 800 ms… hasta 4 intentos | `REINTENTOS_AGOTADOS` si nunca funcionó |
| Evento repetido | Se descarta sin volver a contarlo | — |

**Duplicados.** Kinesis puede entregar un registro más de una vez: si una instancia muere antes de hacer checkpoint, otra relee desde el último checkpoint. Por eso cada evento se aplica en **una transacción de DynamoDB** que también escribe la marca `PROCESADO#<aplicación>/<idEvento>`, condicionada a que no exista. Si ya existía, la transacción completa se cancela y no se suma nada. Así la entrega es "al menos una vez", pero el efecto es "exactamente una vez".

**Checkpoint después de procesar.** Se hace al final de cada lote, cuando todos sus registros ya terminaron en una de las rutas anteriores.

**Logs.** Cada línea lleva aplicación, `idEvento`, intento, causa, shard y número de secuencia.

### 2.5 El microservicio administrador (`admin-service`, ampliado)

Ahora también administra Kinesis. Toda la lógica está en servicios dedicados (`StreamAdminService`, `StreamTopologiaService`, `StreamMonitoreoService`); los controladores solo los llaman, y el resto de la aplicación (el panel del frontend) usa esta API sin tocar el SDK.

| Endpoint | Qué hace |
|---|---|
| `GET/POST /admin/streams` | Listar y crear streams |
| `GET /admin/streams/{nombre}` | Describir: estado, modo, retención y shards con sus rangos de hash |
| `PUT /admin/streams/{nombre}/shards` | **Resharding** (de 3 a 4 shards, por ejemplo) |
| `PUT /admin/streams/{nombre}/retencion` | Cambiar retención |
| `DELETE /admin/streams/{nombre}` | Eliminar |
| `POST /admin/streams/topologia` | Crea o ajusta **todo lo declarado** (streams, métricas por shard, tabla de estado, alarma). Es idempotente: infraestructura como código |
| `DELETE /admin/streams/topologia` | Borra todo al terminar (Kinesis cobra por shard y hora) |
| `GET /admin/aplicaciones/{nombre}` | Lee la tabla de coordinación: shard, instancia dueña, checkpoint y **retraso** |
| `GET /admin/streams/{nombre}/registros` | Visor de datos (por ejemplo, ver la causa de cada fallido) |
| `GET /admin/streams/{nombre}/metricas` | Métricas de CloudWatch del stream y por shard |
| `GET /admin/streams/alarma` | Estado de la alarma |
| `POST /admin/streams/pruebas` | Publica eventos de prueba: válido, duplicado, inválido, ilegible, incoherente |
| `PUT /admin/aplicaciones/{nombre}/falla` | Induce fallas para la demo (en todas las instancias) |

### 2.6 La alarma de CloudWatch

`pedido360-pedidos-retraso-consumo` vigila `GetRecords.IteratorAgeMilliseconds` del stream de pedidos: la **edad del registro más viejo que se está entregando**. Si pasa de 60 s en 2 de 3 minutos, entra en ALARM.
- **Qué anticipa**: un consumidor caído o lento, **antes** de que los eventos venzan por retención y se pierdan sin procesar.
- **Detalle importante**: la métrica solo existe cuando alguien lee el stream. Por eso "sin datos" también cuenta como alarma: significa que nadie está consumiendo.

### 2.7 El frontend

- **Nueva pestaña "Streaming"** en el panel del administrador:
  - el tablero en vivo (se actualiza cada 3 s, sin recargar);
  - los botones de eventos de prueba y de fallas inducidas;
  - la tabla de streams, para cambiar shards o retención y eliminar;
  - la tabla de coordinación de cada aplicación;
  - la alarma, las métricas y el visor de fallidos.
- **Vista del cliente**: agregar o quitar del carrito, buscar y filtrar por categoría generan eventos de actividad, que se envían en lote cada 4 s.

---

## 3. Recorrido de un evento, de punta a punta

1. El cliente inicia sesión en el frontend con **Cognito** y recibe un JWT.
2. Confirma un pedido: la petición va al **API Gateway**, que valida el JWT, y de ahí al **backend** en EC2.
3. El backend guarda el pedido en **RDS** y publica el evento:
   - en **SNS**, y desde ahí a las 3 colas SQS (Parcial 2, sin cambios);
   - en **Kinesis** `pedido360-pedidos`, con clave `pedidoId`. Kinesis lo asigna a un shard según el hash de la clave y lo replica en 3 zonas.
4. En `streaming-service`, la instancia dueña de ese shard lo lee **dos veces**, una por aplicación:
   - `tablero-ventas` suma la venta;
   - `seguimiento-cocina` registra que el pedido entró a PENDIENTE.
   Cada una hace su checkpoint.
5. El panel del administrador muestra la venta y el pedido en cocina **en unos segundos, sin recargar**.
6. Si el evento viene malo, la aplicación lo deriva a `pedido360-fallidos` con la causa, y el shard sigue avanzando.

---

## 4. Archivos nuevos o modificados

| Dónde | Qué |
|---|---|
| `streaming-service/` (nuevo) | Las 3 aplicaciones consumidoras con la KCL |
| `backend/.../streaming/` (nuevo) | DTO de eventos, productores, configuración |
| `backend/.../controller/ActividadController.java` (nuevo) | `POST /api/actividad` |
| `backend/.../service/PedidoService.java` | Publica también en Kinesis |
| `admin-service/.../service/Stream*.java` (nuevos) | Administración de streams, topología, monitoreo y pruebas |
| `admin-service/.../controller/StreamController.java`, `AplicacionConsumidoraController.java` (nuevos) | API REST |
| `admin-service/src/main/resources/application.yml` | Declaración justificada de streams, aplicaciones y alarma |
| `frontend/src/views/PanelStreaming.jsx`, `lib/streaming.js`, `lib/actividad.js` (nuevos) | Panel y registro de actividad |
| `scripts/streaming-crear.bat`, `streaming-eliminar.bat` (nuevos) | Crear o eliminar la plataforma |
| `scripts/construir.bat`, `presentacion.bat`, `detener.bat` | Incluyen `streaming-service` |

## 5. Pruebas automáticas

- `streaming-service`: 20 pruebas.
  - Las 9 rutas del manejo de errores (ilegible, inválido, no recuperable, transitorio que se recupera, transitorio que agota intentos, duplicado, un evento malo que no detiene a los siguientes, fallidos caído).
  - Los tres dominios.
  - La traducción a DynamoDB.
  - Que el contexto levante.
- `backend`: el reenvío de solo los registros rechazados en `PutRecords`, más la prueba de contexto que ya existía.
- `admin-service`: que la declaración cumpla la pauta (2 streams de 3 shards más el de fallidos, 2 aplicaciones sobre el mismo stream, alarma sobre la edad del iterador).
