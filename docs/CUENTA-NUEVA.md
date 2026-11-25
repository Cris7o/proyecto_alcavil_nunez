# Montar Pedido360 en otra cuenta de AWS (Learner Lab)

Guía para levantar el proyecto completo (Parciales 1, 2 y 3) en una cuenta de AWS Academy distinta de la original, por ejemplo si a la primera se le acabó el crédito.

**Ningún identificador de cuenta está escrito en el código.** Lo propio de cada cuenta se pasa por variables de entorno. Lo que no tiene código (Cognito, API Gateway, EC2, RDS) se recrea en la consola.

---

## 0. Credenciales en el equipo

1. Iniciar el Learner Lab de la cuenta nueva (**Start Lab**, esperar el punto verde).
2. **AWS Details** → **AWS CLI: Show** → copiar el bloque que empieza con `[default]`.
3. Pegarlo en `C:\Users\<usuario>\.aws\credentials`, reemplazando lo anterior.

Las credenciales del Learner Lab duran unas 4 horas: si un servicio empieza a decir `The security token included in the request is invalid`, repetir este paso.

---

## 1. Lo que se crea solo (con el administrador)

| Recurso | Quién lo crea | Cuándo |
|---|---|---|
| Tópico SNS, 3 colas SQS, 3 DLQ, suscripciones con filtros, alarmas de las DLQ | `admin-service` | Al arrancar (`DECLARAR_TOPOLOGIA=true`, por defecto) |
| 3 streams Kinesis, métricas por shard, tabla `pedido360-streaming-estado` con TTL, alarma de retraso | `admin-service` | `POST /admin/streams/topologia` o `scripts\streaming-crear.bat` |
| Tablas de coordinación de la KCL (una por aplicación, más `-CoordinatorState` y `-WorkerMetricStats`) | `streaming-service` (la KCL) | Al arrancar la primera instancia. Medido: unos **10 minutos** hasta que DynamoDB deja `ACTIVE` el índice `LeaseOwnerToLeaseKeyIndex`; recién ahí se toman los leases. Levantar con tiempo |

**Restricción del Learner Lab (comprobada):** una política de la organización (SCP) deniega `kinesis:IncreaseStreamRetentionPeriod`. Por eso todos los streams quedan en 24 h de retención. La declaración lo tolera: si AWS deniega un paso, lo informa como `DENEGADO` y sigue con el resto.

---

## 2. Lo que se recrea en la consola (Parcial 1)

### 2.1 Cognito (IDaaS)

1. **Cognito → User pools → Create user pool**: inicio de sesión con email.
2. Crear un **App client** de tipo SPA, sin secreto.
3. Crear los grupos `CLIENTE`, `COCINA`, `REPARTIDOR` y `ADMIN`.
4. Crear los usuarios de la demo y asignar cada uno a su grupo.
5. Anotar el **User Pool ID** (`us-east-1_XXXX`) y el **App client ID**.

### 2.2 RDS (MySQL)

1. **RDS → Create database → MySQL**, plantilla *Free tier* o *Dev/Test*, instancia `db.t3.micro`.
2. Usuario y contraseña propios. **No se escriben en el repositorio**: se pasan como `DB_USERNAME` y `DB_PASSWORD`.
3. Security group: permitir el puerto 3306 **solo desde el security group de la EC2**.
4. Crear la base `pedidos360`. Hibernate crea las tablas solo (`ddl-auto=update`).

### 2.3 EC2 (backend)

1. Lanzar una Amazon Linux 2023 `t3.micro` con Java 17 (`sudo dnf install -y java-17-amazon-corretto`).
2. **Rol de la instancia: `LabInstanceProfile`**. Con él, el backend publica en SNS y Kinesis sin claves en el repositorio.
3. Copiar `backend/target/backend-0.0.1-SNAPSHOT.jar` con `scp` y levantarlo con estas variables:

```
SPRING_PROFILES_ACTIVE=cognito
DB_URL=jdbc:mysql://<endpoint-rds>:3306/pedidos360
DB_USERNAME=...
DB_PASSWORD=...
COGNITO_ISSUER_URI=https://cognito-idp.us-east-1.amazonaws.com/<USER_POOL_ID>
COGNITO_APP_CLIENT_ID=<APP_CLIENT_ID>
```

### 2.4 API Gateway (API Manager)

1. **HTTP API** con integración HTTP hacia `http://<ip-ec2>:8080/api/{proxy}`.
2. Ruta `ANY /api/{proxy+}`. Con eso ya queda cubierto el endpoint nuevo `POST /api/actividad`.
3. **Authorizer JWT** de Cognito: issuer `https://cognito-idp.us-east-1.amazonaws.com/<USER_POOL_ID>`, audience `<APP_CLIENT_ID>`.
4. CORS: permitir los orígenes `http://localhost:4173` y el de Netlify, y la cabecera `Authorization`.
5. Anotar la URL de invocación.

### 2.5 Frontend

Crear `frontend/.env.local` (ya está en `.gitignore`):

```
VITE_COGNITO_USER_POOL_ID=us-east-1_XXXX
VITE_COGNITO_CLIENT_ID=XXXXXXXX
VITE_API_URL=https://<id>.execute-api.us-east-1.amazonaws.com/api
```

Después, `scripts\construir.bat`.

---

## 3. Levantar todo y verificar

1. `scripts\presentacion.bat` levanta el administrador, los consumidores SQS A y B, los streams, las instancias de streaming A y B, y el frontend.
2. Entrar como admin → pestaña **Streaming**:
   - la tabla de streams muestra 3 streams `ACTIVE`;
   - en **Aplicaciones consumidoras**, los shards se reparten entre `A@…` y `B@…`;
   - **Pedido válido** suma una venta en el tablero en unos segundos;
   - **Ilegible**, y luego **Ver últimos fallidos**, muestra el registro con su causa.
3. **Al terminar: `scripts\streaming-eliminar.bat`** (con el administrador aún arriba). Kinesis cobra por shard y por hora mientras el stream exista. El script borra también las tablas de coordinación, porque un stream recreado repite los IDs de shard y los checkpoints viejos ya no le sirven.
