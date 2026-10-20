# Puesta en marcha — paso a paso

Esta guía es para dejar el sistema funcionando desde cero, y para la rutina del día de la presentación.

> **Plazo del encargo: miércoles 21 de octubre, 14:29.** Cualquier commit posterior no se considera. Si al probar aparece algo que haya que corregir, tiene que quedar commiteado **antes** de esa hora.

---

## Estado del proyecto

| Pieza | Estado |
|---|---|
| `backend` — publica eventos en SNS | Compila. **No probado contra AWS.** |
| `mensajeria-service` — los 3 consumidores | Compila, 7 pruebas pasan. **No probado contra AWS.** |
| `admin-service` — API de administración | Compila. **No probado contra AWS.** |
| `frontend` — panel de mensajería | Compila, sin advertencias de lint. **Panel no probado con datos reales.** |
| Topología en AWS | **No creada todavía.** La crea el código. |
| Rol de IAM en la instancia EC2 | **Pendiente, hay que hacerlo a mano.** |
| Jar nuevo del backend en EC2 | **Pendiente de subir.** |

Lo que falta es exactamente esto: ejecutarlo contra AWS por primera vez.

---

## Parte 1 — Prender el laboratorio

### 1.1 Iniciar el lab

AWS Academy → tu curso → **Launch AWS Academy Learner Lab** → **Start Lab**.

Esperas a que el círculo quede **verde**. Después entras a la consola apretando **AWS**.

### 1.2 Encender la instancia EC2

**EC2** → *Instances* → marcas tu instancia → **Instance state → Start instance**.

Anota la **IP pública**. Si tiene Elastic IP asociada (la tiene, la configuramos en EP1), la IP es siempre la misma: `18.214.184.183`.

### 1.3 Encender la base de datos

**RDS** → `database-pedidos360` → **Actions → Start**.

Tarda varios minutos en quedar `Available`. Déjala arrancando y sigue con el resto.

---

## Parte 2 — El rol de IAM en la instancia ← **lo único nuevo en la consola**

Sin esto el backend no puede publicar eventos. Es el paso más importante de toda la guía.

```
EC2 → Instances → marca la casilla de tu instancia
    → Actions → Security → Modify IAM role
    → en el desplegable elige el perfil del laboratorio
      (se llama LabInstanceProfile o LabRole, según la versión)
    → Update IAM role
```

### Qué acabas de hacer

Le colgaste a la máquina una **identidad de AWS**. Desde ese momento, cualquier programa que corra dentro de esa instancia puede pedirle a AWS credenciales temporales sin que nadie las escriba en ningún archivo. AWS las entrega y las renueva solo.

Es la razón por la que **no hay ni una clave de AWS en el repositorio**.

Antes no lo necesitabas porque el backend solo hablaba con la base de datos, y eso va por JDBC con usuario y contraseña. Publicar en SNS sí es una operación de AWS, y para eso hace falta identidad.

### Si el desplegable sale vacío o te lo rechaza

Plan B: pasarle credenciales temporales al servicio en EC2 por variables de entorno. Funciona, pero **expiran con cada sesión del lab**, así que habría que actualizarlas antes de cada demo. Está explicado al final, en *Problemas conocidos*.

---

## Parte 3 — Las credenciales en el equipo

El administrador y los consumidores corren en tu equipo, así que necesitan credenciales propias.

En el lab: **AWS Details** → al lado de *AWS CLI* aprietas **Show**. Copias el bloque completo:

```ini
[default]
aws_access_key_id=ASIA...
aws_secret_access_key=...
aws_session_token=...
```

Y lo pegas en:

```
C:\Users\<tu-usuario>\.aws\credentials
```

Si la carpeta `.aws` no existe, la creas. El archivo se llama `credentials`, **sin extensión**.

> Estas credenciales son temporales y mueren con la sesión del lab. **Hay que repegarlas cada vez** que se reinicia el laboratorio. El backend en EC2 no las necesita, porque ya tiene el rol de la Parte 2.

---

## Parte 4 — Construir y levantar

### 4.1 Construir (una sola vez, cuando cambia el código)

Doble clic en:

```
scripts\construir.bat
```

Compila los tres microservicios y el frontend. Tarda unos minutos la primera vez.

Si el JDK está en otra ruta, hay que editar la línea `set JAVA_HOME=` dentro del `.bat`.

### 4.2 Levantar todo

Doble clic en:

```
scripts\presentacion.bat
```

Abre cuatro ventanas **minimizadas** y al final el navegador:

| Ventana | Puerto | Qué hace |
|---|---|---|
| administrador | 8080 | **Crea la topología completa en AWS** y expone la API |
| consumidor A | 8081 | Lee las tres colas |
| consumidor B | 8082 | Lee las mismas tres colas |
| frontend | 4173 | La aplicación |

El script espera a que el administrador termine de crear la topología antes de levantar los consumidores. Eso importa: si las colas todavía no existen, los consumidores fallan al arrancar a propósito (están configurados con `queue-not-found-strategy: fail`, para que nunca creen una cola sin DLQ).

Para apagar todo: `scripts\detener.bat`.

### 4.3 Entrar

`http://localhost:4173` → entras como **admin@pedido360.cl** → pestaña **Mensajería**.

---

## Parte 5 — Verificar que todo funciona

Esta es la lista de la primera prueba contra AWS. Si algo falla, está en *Problemas conocidos* más abajo.

### 5.1 La topología se creó

Abre la ventana del **administrador** (está minimizada en la barra de tareas) y busca estas líneas:

```
Topico 'pedido360-eventos-pedido' creado o ya existente | arn=arn:aws:sns:...
Cola 'pedido360-notificaciones' creada o actualizada | dlq=pedido360-notificaciones-dlq
Suscripcion 'pedido360-eventos-pedido' -> cola 'pedido360-notificaciones' | patron=entrega selectiva
...
Alarma 'pedido360-notificaciones-dlq-con-mensajes' declarada sobre ...
Topologia aplicada: topico 'pedido360-eventos-pedido' con 3 colas
```

Si dice `Topologia aplicada ... con 3 colas`, listo.

**Confírmalo en la consola de AWS:**
- **SQS** → tienen que aparecer **6 colas** (3 principales + 3 DLQ)
- **SNS** → *Topics* → `pedido360-eventos-pedido` → *Subscriptions* → **3 suscripciones**
- **CloudWatch** → *Alarms* → **3 alarmas**, una por DLQ

### 5.2 Los consumidores están leyendo

En la ventana del **consumidor A**, al arrancar:

```
Instancia 'A' escuchando el topico 'pedido360-eventos-pedido' a traves de:
  consumidor 'notificaciones' -> cola 'pedido360-notificaciones' (DLQ '...', 3 intentos antes de derivar)
  consumidor 'documentos' -> cola 'pedido360-documentos' (DLQ '...', 5 intentos antes de derivar)
  consumidor 'auditoria' -> cola 'pedido360-auditoria' (DLQ '...', 3 intentos antes de derivar)
```

En el panel, la sección **Los consumidores** debe mostrar `A activo` y `B activo` en los tres dominios.

### 5.3 Fanout

En el panel, botón **Pedido creado**.

Los tres contadores *En cola* suben y bajan en un par de segundos (los consumidores se lo llevan al instante). En **Qué está pasando ahora** aparecen tres líneas:

```
[A] Notificaciones  procesado
[B] Documentos      procesado
[A] Auditoria       procesado
```

Las letras pueden ser cualquier combinación de A y B — eso es SQS repartiendo.

**Eso es fanout:** un evento publicado llegó a las tres colas.

### 5.4 Entrega selectiva ← el que hay que mirar con atención

Botón **En preparación**.

Tiene que aparecer **una sola línea**, de auditoría:

```
[B] Auditoria  procesado
```

Si aparecen líneas de notificaciones o documentos, **los filtros no están funcionando** y hay que revisarlo.

**Por qué:** notificaciones y documentos tienen filtros de suscripción que solo dejan pasar ciertos tipos de evento. SNS descarta el resto **antes** de que toque una cola. Auditoría no tiene filtro, así que recibe todo.

### 5.5 Duplicados

Botón **Repetir el último**. Publica otra vez el mismo `idEvento`.

Tiene que aparecer:

```
[A] Auditoria  duplicado, descartado
```

**Por qué:** SQS garantiza entrega *al menos una vez*, no exactamente una. Sin este control, un pedido entregado podría generar dos boletas.

> Ojo con un detalle honesto: el registro de duplicados es **local a cada instancia**. Si el duplicado le toca a la otra instancia, no lo detecta. En producción esto iría en una tabla compartida. Está documentado en el código y conviene decirlo si preguntan — queda mejor reconocerlo que que lo descubran.

### 5.6 Las dos instancias repartiéndose la cola

Aprieta **Pedido creado** unas 8 veces seguidas, rápido.

En la lista van a aparecer líneas de `[A]` y `[B]` entremezcladas. Eso es el reparto.

### 5.7 Pausar sin perder mensajes

1. En **Notificaciones**, aprieta **Pausar**
2. Los dos puntos pasan a `pausado` (ámbar)
3. Aprieta **Pedido creado** tres veces
4. El contador *En cola* de notificaciones **sube a 3 y se queda ahí** — nadie los está tomando
5. Las otras dos colas sí procesaron, porque sus consumidores siguen activos
6. Aprieta **Reanudar**
7. Los 3 se procesan de golpe y el contador vuelve a 0

**Por qué no se perdió nada:** un mensaje no sale de la cola hasta que alguien lo **confirma**. Un consumidor pausado no confirma nada, así que los mensajes se quedan esperando.

### 5.8 Flujo fallido hasta la DLQ con reintentos

1. En **Notificaciones**, en el selector elige **Falla recuperable**
2. Aprieta **Pedido creado**
3. Mira la lista. Cada ~30 segundos (el visibility timeout) aparece un intento:

```
[A] Notificaciones  se reintentara   Intento 1 de 3: Falla transitoria inducida...
[A] Notificaciones  se reintentara   Intento 2 de 3: ...
[A] Notificaciones  a la DLQ         Reintentos agotados (3 de 3): ...
```

4. El contador *En DLQ* sube a 1 y la tarjeta se pone ámbar
5. **Ver DLQ** → aparece el mensaje con su **id de evento, intento y causa**
6. Vuelve el selector a **Sin falla**

> Esto tarda unos 90 segundos en total, porque entre intento e intento SQS espera el visibility timeout de 30 s. **No lo apures en la presentación**: explícalo mientras pasa, que justamente es el momento de contar cómo funciona el reintento.

Con **Falla permanente** cae a la DLQ en el primer intento, sin reintentos. Esa es la diferencia entre los dos caminos de error.

### 5.9 La alarma de CloudWatch

Con un mensaje en la DLQ, en **CloudWatch** → *Alarms* la alarma correspondiente pasa a **In alarm**.

> Tarda hasta **5 minutos**, porque CloudWatch publica las métricas de SQS cada 5 minutos. No es un error. En la presentación conviene provocar la falla temprano para que la alarma ya esté encendida cuando llegues a esa parte.

### 5.10 Reprocesar la DLQ

Con el mensaje en la DLQ y el selector en **Sin falla**:

**Ver DLQ** → **Reprocesar**.

El mensaje vuelve a su cola de origen, el consumidor lo procesa bien, y el contador de la DLQ vuelve a 0.

### 5.11 El backend en EC2 ← falta subir el jar nuevo

Esto todavía no está hecho. El jar cambió: ahora publica eventos.

```bash
# 1. Copiar el jar nuevo a la instancia
scp -i C:\Users\<tu-usuario>\Downloads\pedidos360key.pem ^
    backend\target\backend-0.0.1-SNAPSHOT.jar ^
    ec2-user@18.214.184.183:/home/ec2-user/backend.jar

# 2. Entrar
ssh -i C:\Users\<tu-usuario>\Downloads\pedidos360key.pem ec2-user@18.214.184.183

# 3. Reiniciar el servicio
sudo systemctl restart pedido360
sudo systemctl status pedido360

# 4. Ver que arrancó y que resolvió el topico
sudo journalctl -u pedido360 -f
```

En el log tiene que aparecer:

```
ARN del topico 'pedido360-eventos-pedido' resuelto como arn:aws:sns:us-east-1:...
```

Si en cambio aparece un error de credenciales o `AccessDenied`, es que falta la **Parte 2** (el rol de IAM).

> El nombre del servicio (`pedido360`) y la ruta del jar pueden ser distintos. Para verlo: `systemctl list-units | grep -i pedido`.

### 5.12 El flujo completo de punta a punta

Lo último, y lo que realmente pide la pauta:

1. En la app, cierra sesión y entra como **cliente@pedido360.cl**
2. Agrega un producto y confirma el pedido
3. Vuelve a entrar como **admin@pedido360.cl** → pestaña **Mensajería**
4. En la actividad tiene que aparecer el evento de **ese** pedido

Eso demuestra la cadena entera: frontend → Cognito → API Gateway → backend en EC2 → SNS → las tres colas → los consumidores.

---

## Parte 6 — Rutina del día de la presentación

Resumen, sin explicaciones. Para hacerlo 20 minutos antes de entrar.

```
1. Start Lab                            (esperar círculo verde)
2. EC2    → Start instance
3. RDS    → Actions → Start             (tarda, hazlo primero)
4. AWS Details → Show → copiar el bloque
   → pegar en  C:\Users\<usuario>\.aws\credentials
5. Doble clic en  scripts\presentacion.bat
6. Entrar como admin@pedido360.cl → pestaña Mensajería
7. Provocar una falla recuperable YA, para que la alarma
   de CloudWatch esté encendida cuando llegues a esa parte
8. Dejar abiertas estas pestañas:
   - la app                 (localhost:4173)
   - SQS                    (las 6 colas)
   - SNS                    (las 3 suscripciones)
   - CloudWatch             (las 3 alarmas)
```

El rol de IAM de la Parte 2 **no hay que repetirlo**: queda puesto para siempre.

---

## Problemas conocidos y qué hacer

### El administrador dice `The security token included in the request is invalid`

Las credenciales del lab expiraron o no están pegadas. Repite la **Parte 3** y reinicia el administrador.

### El administrador dice `AccessDenied` al crear la alarma

El `LabRole` no permite crear alarmas de CloudWatch. El código avisa y **sigue adelante**: el resto de la topología se crea igual.

En ese caso hay que crear las 3 alarmas a mano, una por DLQ:

```
CloudWatch → Alarms → Create alarm → Select metric
  → SQS → Queue Metrics
  → busca la DLQ → marca  ApproximateNumberOfMessagesVisible
  → Statistic: Maximum   Period: 5 minutes
  → Threshold: Greater/Equal than 1
  → Missing data treatment: Treat as good (not breaching)
  → sin notificación (Remove)
  → nombre:  pedido360-<cola>-dlq-con-mensajes
```

### Los consumidores no arrancan: `QueueAttributesResolvingException`

Las colas no existen todavía. Arranca primero el administrador y espera a que diga `Topologia aplicada`. El `presentacion.bat` ya hace esta espera; si levantaste los servicios a mano, respeta el orden.

### El panel dice "El microservicio administrador no responde"

El administrador no está levantado, o se cayó. Abre su ventana y mira el error.

### Publico un evento y no llega a ninguna cola

Casi seguro es el permiso de la cola. El código lo configura solo (`permitirEscrituraDesdeTopico`), pero conviene verificarlo:

```
SQS → la cola → pestaña Access policy
```

Tiene que haber una sentencia que permita a `sns.amazonaws.com` hacer `sqs:SendMessage`.

> Este es un detalle que la consola de AWS hace sola cuando suscribes a clics, y el SDK **no**. Sin ese permiso la suscripción queda creada pero los mensajes nunca llegan, y no aparece ningún error en ninguna parte. Es una buena anécdota para la presentación.

### Un evento llega a colas que no corresponden

El filtro de suscripción no se aplicó. Revisa:

```
SNS → Topics → pedido360-eventos-pedido → Subscriptions
    → la suscripción → pestaña Subscription filter policy
```

Para forzar que se reapliquen: `POST http://localhost:8080/admin/topologia`.

### El backend en EC2 no publica

Por orden de probabilidad:

1. Falta el rol de IAM (**Parte 2**). En el log sale `AccessDenied` o un error de credenciales.
2. No se subió el jar nuevo (**5.11**). En el log no aparece ninguna línea sobre el tópico.
3. El perfil activo no es `cognito`. El perfil `local` tiene la publicación apagada a propósito.

**Plan B si el rol de IAM no se puede poner:** agregar las credenciales temporales al archivo de entorno del servicio en EC2.

```bash
ssh -i <llave> ec2-user@18.214.184.183
sudo nano /etc/pedido360.env     # la ruta puede variar: revisa el EnvironmentFile del servicio
```

Agregar:

```
AWS_ACCESS_KEY_ID=ASIA...
AWS_SECRET_ACCESS_KEY=...
AWS_SESSION_TOKEN=...
AWS_REGION=us-east-1
```

Y después `sudo systemctl restart pedido360`.

> Esto **expira con cada sesión del lab**, así que habría que repetirlo antes de cada demo. El rol de IAM es mucho mejor. Y estas credenciales **nunca** van al repositorio.

### RDS tarda mucho en arrancar

Es normal, varios minutos. Enciéndela primero y haz el resto mientras.
