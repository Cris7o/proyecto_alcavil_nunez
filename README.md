# Pedido360

Sistema de pedidos para un local de comida, construido sobre AWS.

**DSY1107 — Desarrollo Cloud Native I** · Escuela de Informática y Telecomunicaciones, Duoc UC
Cristóbal Alcavil · Cristóbal Núñez

---

## Qué resuelve

En un local de comida hay cuatro personas involucradas en un mismo pedido y ninguna mira la misma pantalla: el **cliente** pide, la **cocina** prepara, el **repartidor** entrega y el **administrador** supervisa. Pedido360 le da a cada rol su propia vista y gobierna el ciclo del pedido con reglas que se aplican en el servidor, no en la interfaz.

Sobre eso, la segunda etapa agrega una **capa de mensajería asíncrona** con Amazon SNS y SQS: cuando un pedido cambia de estado, el sistema publica un evento y tres consumidores independientes reaccionan sin que el cliente tenga que esperarlos.

---

## Estructura del repositorio

| Carpeta | Qué es | Puerto |
|---|---|---|
| `backend/` | Servicio de pedidos. API REST del negocio y **productor** de eventos. | 8080 en EC2 |
| `mensajeria-service/` | **Consumidores** SQS, agrupados por dominio funcional. | 8081 / 8082 |
| `admin-service/` | **Microservicio administrador** de la mensajería. | 8080 local |
| `frontend/` | Aplicación React con login vía Cognito y panel de mensajería. | Netlify / 4173 |
| `scripts/` | Levantar y apagar todo con un doble clic. | — |
| `docs/` | Puesta en marcha, diagramas y guion de la presentación. | — |

### Documentación

| Documento | Para qué |
|---|---|
| [`docs/PUESTA-EN-MARCHA.md`](docs/PUESTA-EN-MARCHA.md) | Paso a paso de AWS, cómo levantar todo, cómo verificarlo y qué hacer si algo falla |
| [`docs/arquitectura.md`](docs/arquitectura.md) | Diagrama de arquitectura y recorrido de un mensaje hasta su confirmación, reintento o DLQ |
| [`docs/presentacion.md`](docs/presentacion.md) | Qué mostrar, en qué orden y qué explicar en cada parte |

Cada carpeta es un proyecto independiente con su propio `pom.xml` o `package.json`.

---

## Arquitectura

```
                    ┌──────────────┐
                    │   Cognito    │  emite el JWT
                    └──────┬───────┘
                           │
   ┌──────────┐     ┌──────▼────────┐     ┌──────────────────┐
   │ frontend │────▶│  API Gateway  │────▶│     backend      │
   │  React   │ JWT │ (API Manager) │     │ servicio pedidos │
   └──────────┘     │ autorizador   │     │   en EC2         │
                    └───────────────┘     └────┬──────┬──────┘
                                               │      │
                                        ┌──────▼──┐  ┌▼──────────┐
                                        │   RDS   │  │    SNS    │
                                        │  MySQL  │  │  tópico   │
                                        └─────────┘  └─────┬─────┘
                                                           │
                  ┌────────────────────┬───────────────────┴──┐
                  │ filtro             │ filtro               │ sin filtro
                  ▼                    ▼                      ▼
         ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────┐
         │ notificaciones  │  │   documentos    │  │    auditoría     │
         │      + DLQ      │  │     + DLQ       │  │      + DLQ       │
         └────────┬────────┘  └────────┬────────┘  └────────┬─────────┘
                  └────────────────────┼────────────────────┘
                                       ▼
                            ┌─────────────────────┐
                            │ mensajeria-service  │  ×2 instancias
                            └─────────────────────┘
                                       ▲
                            ┌──────────┴──────────┐
                            │    admin-service    │  plano de control
                            └─────────────────────┘
                                       │
                            ┌──────────▼──────────┐
                            │ CloudWatch: alarma  │
                            │   por cada DLQ      │
                            └─────────────────────┘
```

El navegador nunca le habla directo al backend: todo entra por API Gateway, que valida el JWT antes de dejar pasar nada. La base de datos no tiene IP pública. El backend valida el token **otra vez** por defensa en profundidad, y recién entonces evalúa si el rol puede hacer la operación.

---

## La mensajería

### Un tópico, tres colas, tres DLQ

| Cola | Para qué | Retención | Visibility | maxReceiveCount | DLQ |
|---|---|---|---|---|---|
| `pedido360-notificaciones` | avisar al cliente | 4 días | 30 s | 5 | `…-notificaciones-dlq` |
| `pedido360-documentos` | comanda de cocina y boleta | 14 días | 60 s | 8 | `…-documentos-dlq` |
| `pedido360-auditoria` | bitácora completa | 7 días | 30 s | 5 | `…-auditoria-dlq` |

Las tres DLQ retienen **14 días**, el máximo que permite SQS: un mensaje que falló hay que alcanzar a diagnosticarlo.

Las justificaciones completas de cada política están en
[`admin-service/src/main/resources/application.yml`](admin-service/src/main/resources/application.yml), junto a los valores que justifican.

### Los dos patrones de enrutamiento

**Fanout** — `auditoria` está suscrita **sin filtro**, así que recibe absolutamente todos los eventos. Un `PEDIDO_CREADO` cae en las tres colas a la vez.

**Entrega selectiva** — `notificaciones` y `documentos` tienen filtros de suscripción distintos sobre el atributo `tipoEvento`:

```
notificaciones  →  PEDIDO_CREADO, PEDIDO_LISTO, PEDIDO_ENTREGADO, PEDIDO_CANCELADO
documentos      →  PEDIDO_CREADO, PEDIDO_ENTREGADO
auditoría       →  (sin filtro)
```

La consecuencia es comprobable: un `PEDIDO_EN_PREPARACION` llega **solo a auditoría**. Las otras dos lo descartan en SNS, antes de que toque una cola.

### Qué hace cada consumidor cuando algo falla

Toda la política vive en una sola clase,
[`ProcesadorEventos`](mensajeria-service/src/main/java/com/pedidos360/mensajeria/comun/ProcesadorEventos.java),
y los tres consumidores delegan en ella. El modo de confirmación es **`MANUAL`**: ningún mensaje sale de la cola salvo que el código llame explícitamente a `acknowledge()`.

| Situación | Qué hace | Confirma |
|---|---|---|
| Éxito | marca el `idEvento` como procesado | sí |
| Duplicado | lo descarta sin volver a ejecutar la acción | sí |
| Error **no recuperable** | lo deriva a la DLQ con la causa, sin gastar reintentos | sí |
| Error **recuperable**, quedan intentos | **no confirma**: SQS lo reentrega al expirar el visibility timeout | no |
| Error **recuperable**, agotados | lo deriva a la DLQ con la causa | sí |

Un detalle que importa: cuando el consumidor deriva un mensaje, le **adjunta la causa** como atributo (`causa`, `intento`, `colaOrigen`, `idEvento`). La Redrive Policy de AWS también mueve mensajes a la DLQ, pero sin explicar por qué fallaron. El `maxReceiveCount` está deliberadamente por encima de los reintentos del consumidor: el consumidor deriva primero, y la Redrive Policy queda como red de seguridad para el caso en que el proceso muera antes de alcanzar a hacerlo.

**No hay ningún `@SqsListener` sobre las DLQ**, y es a propósito: un consumidor sobre la DLQ borraría los mensajes al leerlos, y entonces no quedarían visibles en la consola ni dispararían la alarma. Las DLQ se inspeccionan y se reprocesan desde el administrador, bajo demanda.

---

## API del microservicio administrador

### Topología

```
GET    /admin/topologia      la topología declarada, con sus justificaciones
POST   /admin/topologia      la aplica sobre AWS (idempotente)
DELETE /admin/topologia      la elimina entera
```

`POST /admin/topologia` crea el tópico, las tres colas con su retención y visibility timeout, las tres DLQ, las Redrive Policy que las asocian, las tres suscripciones con su filtro, y una alarma de CloudWatch por DLQ. Es idempotente: se puede ejecutar cuantas veces se quiera.

### Colas

```
GET    /admin/colas                  lista las colas del proyecto
POST   /admin/colas                  crea una cola, con DLQ y Redrive Policy
GET    /admin/colas/estado           estado de las tres colas declaradas
GET    /admin/colas/{nombre}         estado de una cola y de su DLQ
DELETE /admin/colas/{nombre}         elimina una cola
POST   /admin/colas/{nombre}/purga   vacía una cola
```

### Tópicos y suscripciones

```
GET    /admin/topicos                            lista los tópicos
POST   /admin/topicos                            crea un tópico
DELETE /admin/topicos/{nombre}                   elimina un tópico
GET    /admin/topicos/{nombre}/suscripciones     lista las suscripciones
POST   /admin/topicos/suscripciones              suscribe una cola
PUT    /admin/topicos/suscripciones/filtro       cambia el filtro en caliente
DELETE /admin/topicos/suscripciones              elimina una suscripción
POST   /admin/topicos/publicaciones-prueba       publica un evento sintético
```

### Consumidores

```
GET  /admin/consumidores                        estado de cada instancia
POST /admin/consumidores/{id}/pausar            deja de leer la cola
POST /admin/consumidores/{id}/reanudar          vuelve a leer la cola
POST /admin/consumidores/{id}/falla-inducida    provoca una falla, para demostrar
```

Los contenedores de los consumidores viven en el `mensajeria-service`, no en el administrador, así que el administrador **reenvía la orden por HTTP** a cada instancia registrada. Es el patrón de un plano de control. La orden va a todas las instancias: con dos leyendo la misma cola, pausar solo una no detendría el consumo.

### DLQ

```
GET  /admin/dlq/{id}                 inspecciona la DLQ sin consumirla
POST /admin/dlq/{id}/reproceso       devuelve los mensajes a su cola de origen
POST /admin/dlq/{id}/purga           vacía la DLQ
```

`{id}` es el identificador lógico (`notificaciones`, `documentos`, `auditoria`), no el nombre del recurso en AWS: quien opera la API no tiene por qué saber cómo se llaman las colas.

### Monitoreo

```
GET  /admin/monitoreo/metricas   las cuatro métricas de cada cola, interpretadas
GET  /admin/monitoreo/alarmas    estado de las alarmas de las DLQ
POST /admin/monitoreo/alarmas    declara o actualiza las alarmas
```

---

## Cómo se configura

Los nombres de colas, tópicos y suscripciones **no aparecen en el código Java**. Cada microservicio los recibe desde su archivo de configuración:

| Servicio | Archivo | Qué declara |
|---|---|---|
| `admin-service` | `application.yml` | la topología completa: nombres, retención, visibility, Redrive Policy, filtros, justificaciones |
| `mensajeria-service` | `application.yml` | los nombres de las tres colas y sus DLQ, y los reintentos de cada una |
| `backend` | `application.properties` | solo el nombre del tópico: no conoce las colas |

La fuente de verdad es el `application.yml` del `admin-service`, porque es el que aplica la topología sobre AWS.

Que el backend no conozca las colas es deliberado: se pueden agregar o quitar consumidores sin tocar ni redesplegar el servicio de pedidos.

### Credenciales

**No hay ninguna credencial en el repositorio.** Las resuelve `DefaultCredentialsProvider`, que las busca en este orden:

1. variables de entorno (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`)
2. `~/.aws/credentials`
3. el perfil de instancia de EC2

En el equipo se exportan las del Learner Lab, que cambian en cada sesión. En EC2 las aporta el rol de la instancia, así que ahí no hay que exportar nada.

---

## El panel de mensajería

El rol `ADMIN` tiene una pestaña **Mensajería** en la aplicación que muestra la capa asíncrona completa en una sola pantalla, sin tener que leer la consola de cada servicio:

- el tópico y sus tres colas, con los contadores **en vivo** (en cola, en proceso, en DLQ)
- el patrón de cada suscripción (*fanout* o *entrega selectiva*) y qué eventos deja pasar su filtro
- las políticas de retención, visibility timeout y el intento en que se deriva a la DLQ
- el estado de la alarma de CloudWatch de cada DLQ
- las dos instancias de cada consumidor, con su estado y botones de **pausar** y **reanudar**
- el contenido de cada DLQ con **id de evento, intento y causa**, y botones de reprocesar y purgar
- la actividad reciente de las dos instancias en una sola lista, marcada con la instancia que procesó cada mensaje

Los botones de este panel **son** llamadas a la API del microservicio administrador: pausar un consumidor desde la pantalla es el mismo `POST /admin/consumidores/{id}/pausar` que se podría llamar con `curl`.

El panel apunta a `http://localhost:8080` y la dirección se cambia con `VITE_ADMIN_URL`.

> El panel se usa con el frontend servido localmente, porque el administrador y los consumidores corren en el equipo desde el que se presenta. Un sitio HTTPS llamando a `localhost` es algo que el navegador bloquea o pregunta, así que no conviene depender de eso. `http://localhost` sí cuenta como contexto seguro, de modo que Cognito y Amplify funcionan igual.

---

## Cómo se levanta

### Con un doble clic

```
scripts\construir.bat     una vez, cuando cambia el código
scripts\presentacion.bat  levanta administrador, los dos consumidores y el frontend
scripts\detener.bat       apaga todo
```

`presentacion.bat` abre cuatro ventanas minimizadas, espera a que el administrador termine de crear la topología antes de levantar los consumidores, y abre el navegador en `http://localhost:4173`.

Antes hay que iniciar el lab de AWS Academy y pegar el bloque de **AWS Details** en `%USERPROFILE%\.aws\credentials`. El script avisa si falta.

### A mano

#### Requisitos

- JDK 17
- Node 20 para el frontend
- Credenciales del Learner Lab exportadas (salvo en EC2)

#### 1. Exportar las credenciales del laboratorio

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export AWS_SESSION_TOKEN=...
export AWS_REGION=us-east-1
```

#### 2. Administrador — crea la topología al arrancar

```bash
cd admin-service && ./mvnw spring-boot:run
```

#### 3. Consumidores — dos instancias sobre las mismas colas

```bash
cd mensajeria-service && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --pedido360.instancia=A"
```

```bash
cd mensajeria-service && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8082 --pedido360.instancia=B"
```

El nombre de la instancia queda en cada línea de log, que es lo que permite ver cómo SQS reparte los mensajes entre las dos.

#### 4. Servicio de pedidos

En EC2 corre como servicio del sistema y arranca solo. Para desarrollo local, sin nube:

```bash
cd backend && ./mvnw spring-boot:run
```

El perfil `local` usa H2 y deja los eventos en el log en lugar de publicarlos, así se puede probar el flujo de pedidos sin credenciales.

---

## Reglas del negocio

El ciclo del pedido es estrictamente secuencial:

```
PENDIENTE → EN_PREPARACION → LISTO → EN_REPARTO → ENTREGADO
    │
    └──→ CANCELADO
```

| Regla | Si se incumple |
|---|---|
| No se puede saltar un estado | `409 Conflict` |
| Solo la cocina pasa a `EN_PREPARACION` y a `LISTO` | `403 Forbidden` |
| Solo el repartidor pasa a `EN_REPARTO` y a `ENTREGADO` | `403 Forbidden` |
| Un pedido se cancela solo mientras está `PENDIENTE` | `409 Conflict` |
| Un cliente solo cancela sus propios pedidos | `403 Forbidden` |

Estas reglas viven en el backend, en
[`PedidoEstadoService`](backend/src/main/java/com/pedidos360/backend/service/PedidoEstadoService.java),
no en la pantalla. La interfaz solo evita mostrar botones que no corresponden, por comodidad. Esconder un botón no es una medida de seguridad.

---

## Stack

**Backend** · Java 17 · Spring Boot 4.1.1 · Spring Security · Spring Cloud AWS 4.2.0 · AWS SDK v2 · JPA/Hibernate
**Frontend** · React 19 · Vite · AWS Amplify
**AWS** · Cognito · API Gateway · EC2 · RDS MySQL · SNS · SQS · CloudWatch
