# Presentación — qué mostrar y qué entender

**Duración: entre 5 y 10 minutos.** Apunta a 9. Es poco tiempo para mucho, así que el orden importa.

**La evaluación es individual aunque presenten en pareja.** Cada uno tiene que poder fundamentar *todo* el diseño, no solo su parte. Esta guía está escrita para que la puedas dar tú solo.

---

## Dónde están los puntos

Esto es lo que se evalúa, con su peso. El orden de la demo está armado para cubrir todo.

| Indicador | Peso |
|---|---|
| Muestra en AWS el tópico, las colas y las suscripciones con sus parámetros, y explica el porqué | **15%** |
| Dos instancias de un consumidor sobre la misma cola | **15%** |
| Opera el administrador: crear y eliminar, estado, pausar y reanudar, reprocesar la DLQ | **12%** |
| Productores y consumidores funcionando de extremo a extremo | **10%** |
| Políticas de retención y limpieza, justificadas | **10%** |
| Cada cola tiene DLQ: muestra un mensaje llegando, explica maxReceiveCount y retención | **9%** |
| Logs de la DLQ con id, intento y causa, relacionados con el mensaje | **8%** |
| Explica las métricas de monitoreo y la alarma, y qué decisión tomarías | **8%** |
| Tres colas para funcionalidades distintas, y justifica la separación | **7%** |
| Los dos patrones de enrutamiento, y cuándo usar cada uno | **6%** |

Fíjate en algo: **"explica" y "justifica" aparecen en casi todos.** No alcanza con mostrar que funciona. En varios indicadores el 60% dice *"muestra los recursos pero explica pocos parámetros"*. La diferencia entre 60% y 100% es lo que digas, no lo que se vea.

---

## Antes de empezar

Deja estas pestañas abiertas, en este orden:

```
1. La app              http://localhost:4173   (pestaña Mensajería)
2. SQS                 las 6 colas
3. SNS                 las 3 suscripciones
4. CloudWatch          las 3 alarmas
```

Y el `application.yml` del `admin-service` abierto en el editor, por si te piden ver dónde está declarado algo.

---

# El guion

## 0:00 — Qué cambió desde el parcial anterior · 45 segundos

> "En el parcial anterior construimos Pedido360: un sistema de pedidos con cuatro roles, autenticación con Cognito y el backend en EC2 detrás de API Gateway. Lo que agregamos ahora es una capa de mensajería asíncrona con SNS y SQS."

> "El problema que resuelve es este: cuando un cliente confirma un pedido, hay tres cosas que tienen que pasar — avisarle al cliente, generar la comanda para la cocina y registrar el cambio en la bitácora. Antes eso habría sido parte de la misma petición, y el cliente habría tenido que esperar las tres. Ahora el backend guarda el pedido, publica un evento y responde de inmediato. Las tres cosas pasan después, por su cuenta."

**Lo que tienes que entender de esto:** la mensajería asíncrona no sirve para "ir más rápido" en general. Sirve para que el usuario no tenga que esperar cosas que no necesita ver. Y sirve para que si una de esas tres cosas falla, las otras dos sigan funcionando.

---

## 0:45 — La mensajería en la consola de AWS · 1:15 · **15% + 7%**

Vas a la pestaña de **SQS**.

> "Acá están las seis colas: tres principales y la cola de mensajes muertos de cada una."

Abres **`pedido360-notificaciones`** y vas a la pestaña de detalles. Señalas los parámetros uno por uno:

> "Visibility timeout, 30 segundos. Es el tiempo que un mensaje queda invisible para los demás consumidores cuando uno lo toma. Tiene que ser mayor que lo que demora procesarlo: si fuera menor, otro consumidor tomaría el mismo mensaje mientras el primero todavía está trabajando, y el cliente recibiría el aviso dos veces. Treinta segundos porque enviar una notificación es rápido."

> "Message retention, 4 días. Es cuánto vive un mensaje antes de que SQS lo borre solo. Cuatro días porque el valor de una notificación caduca: avisarle a alguien el viernes que su pedido del lunes está listo es peor que no avisarle."

> "Receive message wait time, 20 segundos. Es long polling: el consumidor espera hasta 20 segundos a que haya algo, en lugar de preguntar en vacío todo el tiempo. Baja el costo y baja la latencia."

Después abres **`pedido360-documentos`** y comparas:

> "Fíjense que esta cola tiene valores distintos: visibility timeout de 60 segundos y retención de 14 días. Generar un documento es más lento que enviar un aviso, y una boleta hay que emitirla igual aunque el sistema haya estado caído dos semanas. Los parámetros no son los mismos para todas las colas porque cada una tiene una exigencia distinta."

**Y acá viene la justificación de las tres colas, que es el 7%:**

> "Las separamos en tres porque tienen necesidades operativas distintas. Notificaciones: si el proveedor de correo se cae, no tiene por qué atrasar nada más. Documentos: es lo más crítico, necesita más reintentos y más retención. Auditoría: no puede perder eventos, pero sí puede atrasarse. Si fueran una sola cola, un problema del proveedor de correo atrasaría las boletas y la bitácora."

**Lo que tienes que entender:** la pregunta de fondo no es "cuántas colas" sino "qué cosas tienen que poder fallar por separado". Eso es lo que define una cola.

---

## 2:00 — Provoca la falla AHORA · 30 segundos

Esto es táctico. **La alarma de CloudWatch tarda hasta 5 minutos en encenderse**, porque CloudWatch publica las métricas de SQS cada 5 minutos. Si provocas la falla al final, no te alcanza.

Vas al panel, en **Notificaciones** eliges **Falla recuperable** en el selector y aprietas **Pedido creado**.

> "Les acabo de provocar una falla a propósito en el consumidor de notificaciones. Vamos a volver a esto en un rato, cuando el mensaje haya terminado de reintentarse."

Y sigues. No te quedes mirando.

---

## 2:30 — Los dos patrones de enrutamiento · 1 minuto · **6%**

Vas a la pestaña **SNS** → el tópico → **Subscriptions**.

> "Del tópico cuelgan tres suscripciones, una por cola. Y acá están los dos patrones."

Abres la suscripción de **auditoría** y muestras que no tiene filter policy:

> "Auditoría está suscrita sin filtro. Recibe absolutamente todos los eventos. Ese es el patrón fanout: una sola publicación llega a todas las colas suscritas."

Abres la de **notificaciones** y muestras su filter policy:

> "Notificaciones tiene un filtro sobre el atributo tipoEvento: solo deja pasar creado, listo, entregado y cancelado. Documentos tiene otro, más estrecho: solo creado y entregado. Ese es el patrón de entrega selectiva."

Ahora vuelves al panel y lo **demuestras**:

Aprietas **Pedido creado** → los tres contadores se mueven, aparecen tres líneas en la actividad.

> "Publiqué un pedido creado: llegó a las tres colas. Eso es fanout."

Aprietas **En preparación** → aparece **una sola línea**, de auditoría.

> "Publiqué un pedido en preparación: llegó solo a auditoría. Notificaciones y documentos lo descartaron, y lo importante es *dónde* lo descartaron: en SNS, antes de tocar la cola. No es que el consumidor lo haya ignorado después."

**Cuándo usar cada uno — esto lo pide la pauta explícitamente:**

> "Fanout cuando todos los suscriptores necesitan todo, como la auditoría. Entrega selectiva cuando cada suscriptor solo le sirve una parte. Y conviene filtrar en SNS y no en el consumidor, porque filtrar en SNS no cuesta nada: si filtráramos en el consumidor, estaríamos pagando por almacenar, entregar y procesar mensajes que vamos a botar."

---

## 3:30 — El flujo de punta a punta · 1 minuto · **10%**

Esto es lo que amarra la mensajería con todo lo del parcial anterior.

Cierras sesión, entras como **cliente@pedido360.cl**, agregas un producto, confirmas el pedido.

> "Acabo de hacer un pedido real. Lo que pasó: el frontend inició sesión con Cognito y recibió un token. Ese token viajó en la petición hacia API Gateway, que lo validó contra Cognito antes de dejarla pasar. El backend en EC2 lo validó otra vez, guardó el pedido en la base de datos en RDS, y publicó el evento en el tópico."

Vuelves a entrar como **admin@pedido360.cl** → pestaña **Mensajería**.

> "Y acá está el evento de ese pedido, procesado por los consumidores."

> "El detalle de diseño que vale la pena señalar: el backend no sabe que estas tres colas existen. Publica en el tópico y se desentiende. Por eso podemos agregar o quitar consumidores sin tocar ni redesplegar el servicio de pedidos — que es exactamente el punto de desacoplar con mensajería."

---

## 4:30 — Dos instancias sobre la misma cola · 1 minuto · **15%**

Este indicador vale tanto como el de la consola. No lo apures.

> "Tenemos dos instancias del microservicio de consumidores corriendo, las dos leyendo las mismas tres colas."

Aprietas **Pedido creado** unas 6 veces rápido. En la actividad aparecen líneas de `[A]` y `[B]` mezcladas.

> "Fíjense en las letras entre corchetes: A y B son las dos instancias. SQS reparte los mensajes entre ellas. No hay que configurar nada para eso: el modelo de SQS es que varios consumidores compiten por la misma cola, y cada mensaje se lo lleva uno solo."

Ahora, en **Notificaciones**, aprietas **Pausar**. Los puntos pasan a ámbar.

> "Pausé el consumidor de notificaciones en las dos instancias."

Aprietas **Pedido creado** tres veces.

> "Miren el contador: notificaciones subió a 3 y se quedó ahí. Las otras dos colas sí procesaron, porque sus consumidores siguen activos."

Aprietas **Reanudar**. Los tres se procesan de golpe.

**Y acá viene la explicación, que es la diferencia entre 80% y 100%:**

> "Lo importante es por qué no se perdió ningún mensaje. En SQS un mensaje no desaparece de la cola cuando alguien lo *lee*: desaparece cuando alguien lo **confirma**. Un consumidor pausado no confirma nada, así que los mensajes se quedaron esperando. Y si en vez de pausarlo se hubiera caído la instancia a la mitad de procesar un mensaje, al expirar el visibility timeout ese mensaje habría vuelto a estar disponible y la otra instancia lo habría tomado."

**Lo que tienes que entender:** confirmar es borrar. Es la idea central de SQS. Todo lo demás —reintentos, DLQ, disponibilidad— sale de ahí.

---

## 5:30 — El administrador operando · 1 minuto · **12%**

El 100% de este indicador pide operar **todas** las funciones y **mostrar su efecto en AWS**. O sea: operas desde la API y después te cambias a la consola a mostrar que pasó.

> "Toda la administración de la mensajería está en un microservicio aparte, con su propia API REST. Los botones de este panel son llamadas a esa API."

Ya mostraste **pausar y reanudar** (minuto 4:30) y vas a mostrar **reprocesar la DLQ** (minuto 6:30). Falta **crear y eliminar**:

> "Además de operar los consumidores, la API permite crear y eliminar colas y tópicos."

Desde una terminal o Postman:

```bash
curl -X POST http://localhost:8080/admin/colas \
  -H "Content-Type: application/json" \
  -d '{"nombre":"pedido360-demo","retencionSegundos":3600,"dlqNombre":"pedido360-demo-dlq","maxReceiveCount":3}'
```

Te cambias a la consola de **SQS**, refrescas: aparecieron dos colas nuevas, con la Redrive Policy ya asociada.

```bash
curl -X DELETE http://localhost:8080/admin/colas/pedido360-demo
curl -X DELETE http://localhost:8080/admin/colas/pedido360-demo-dlq
```

Refrescas: desaparecieron.

> "Y toda esta lógica está encapsulada en un servicio dedicado, `ColaAdminService`. Los controladores no tienen una sola línea del SDK de AWS: llaman métodos de alto nivel."

**Si te preguntan por qué el administrador no apaga los consumidores él mismo:**

> "Porque los consumidores viven en otro proceso, y un programa no puede apagarle algo a otro programa por dentro. El administrador le reenvía la orden por HTTP a cada instancia, y cada una detiene su propio consumidor. Es el patrón de un plano de control. Y la orden va a las dos instancias, porque pausar solo una no detendría el consumo."

---

## 6:30 — La DLQ con sus reintentos · 1 minuto · **9% + 8%**

Vuelves a la falla que provocaste en el minuto 2.

En la actividad ya deberían estar las líneas:

```
[A] Notificaciones  se reintentara   Intento 1 de 3: ...
[A] Notificaciones  se reintentara   Intento 2 de 3: ...
[A] Notificaciones  a la DLQ         Reintentos agotados (3 de 3): ...
```

> "Acá está el flujo fallido. El consumidor falló, no confirmó el mensaje, y SQS lo volvió a entregar al expirar el visibility timeout. Eso pasó tres veces, y al tercer intento el consumidor se rindió y lo derivó a la cola de mensajes muertos."

Aprietas **Ver DLQ**:

> "Y acá está el mensaje en la DLQ, con las tres cosas que importan: el id del evento, el intento en que se rindió, y la causa."

**Explica maxReceiveCount y la retención — lo pide el indicador:**

> "La Redrive Policy de la cola tiene maxReceiveCount en 5: después de 5 recepciones fallidas, AWS mueve el mensaje a la DLQ solo. Pero fíjense que nuestro consumidor se rindió al tercer intento, antes. Eso es a propósito."

> "La razón es que AWS mueve el mensaje a la DLQ **sin explicar por qué falló** — no lo sabe. Derivándolo nosotros, le adjuntamos la causa, el intento y la cola de origen como atributos del mensaje. La Redrive Policy queda como red de seguridad, para el caso en que el proceso muera antes de alcanzar a derivar."

> "Y la DLQ retiene 14 días, el máximo que permite SQS, mientras la cola principal retiene 4. Un mensaje que falló hay que alcanzar a diagnosticarlo, y eso puede tomar días."

**Ahora reprocesa** (esto también cuenta para el 12% del administrador):

Pones el selector en **Sin falla** y aprietas **Reprocesar**.

> "Corregí la causa de la falla y reproceso: el mensaje vuelve a su cola de origen, el consumidor lo procesa bien, y la DLQ queda vacía."

**Si te preguntan por qué no hay un consumidor leyendo la DLQ:**

> "Lo pensamos y lo descartamos. Un consumidor sobre la DLQ borraría los mensajes al leerlos, y entonces no quedarían visibles en la consola ni dispararían la alarma. El registro en logs lo hace el consumidor en el momento exacto de rendirse, y la DLQ se inspecciona y se reprocesa desde el administrador, bajo demanda."

---

## 7:30 — Métricas y alarma · 45 segundos · **8%**

El 100% pide explicar las métricas **y qué decisión tomarías con cada una**. Esa segunda parte es la que se olvida.

Vas a **CloudWatch** → *Alarms*. La alarma de la DLQ de notificaciones debería estar **In alarm**.

> "Tenemos una alarma por cada DLQ, sobre la métrica de mensajes visibles, con umbral 1. Cualquier mensaje en una DLQ enciende la alarma."

> "El umbral es 1 y no un número más alto porque un mensaje en la DLQ ya es un pedido que no se procesó. Y usamos el estadístico máximo y no el promedio, porque un promedio de 0,2 mensajes esconde que hubo uno."

> "Está configurada para tratar la falta de datos como estado bueno: que no haya datos significa que no hubo mensajes, no que algo ande mal."

Después las cuatro métricas, **con la decisión al lado**:

| Métrica | Qué dice | Qué harías |
|---|---|---|
| Mensajes disponibles | esperando a que alguien los tome | si sube y no baja, los consumidores no dan abasto o están detenidos: levantar más instancias |
| Mensajes en proceso | tomados y sin confirmar | si se queda alto, hay consumidores que fallan y no confirman: revisar los logs |
| Antigüedad del más antiguo | cuánto lleva esperando el mensaje más viejo | es la que mide el atraso real; si crece, hay un cuello de botella |
| Mensajes en DLQ | lo que ya nadie pudo procesar | cualquier valor mayor que cero: revisar la causa antes de reprocesar |

> "Y la clave es que una métrica sola no dice nada. Si hay mensajes disponibles y ninguno en proceso, probablemente no hay consumidores leyendo. Si hay muchos en proceso y la antigüedad crece, los consumidores están tomando mensajes pero no logrando terminarlos. Es la combinación la que dice qué está pasando."

---

## 8:15 — Retención y limpieza · 45 segundos · **10%**

El 100% pide justificar la retención por cola, la de la DLQ **y la purga**, y explicar cómo eso evita el crecimiento descontrolado.

| Cola | Retención | Por qué |
|---|---|---|
| notificaciones | 4 días | el valor de una notificación caduca |
| documentos | 14 días | la boleta hay que emitirla igual pasado el incidente |
| auditoría | 7 días | cubre reclamos de la semana; la bitácora de largo plazo no es trabajo de una cola |
| las 3 DLQ | 14 días | el máximo de SQS: hay que alcanzar a diagnosticar |

> "Hay tres frenos al crecimiento, y actúan en capas distintas."

> "El primero es la retención: SQS borra solo los mensajes que superan el plazo. Eso pone un techo a cuánto puede crecer una cola sin que nadie intervenga."

> "El segundo es la Redrive Policy: un mensaje que falla no se queda reintentando para siempre, se va a la DLQ después de un número fijo de recepciones."

> "El tercero es la purga manual, desde el administrador, para los mensajes que ya se diagnosticaron y no vale la pena reprocesar."

> "Y en las DLQ la política es deliberadamente distinta: retienen más tiempo que la cola principal y se limpian a mano. Purgar una DLQ automáticamente sería borrar la evidencia de un problema antes de haberlo mirado."

---

## 9:00 — Cierre

> "Eso es la capa de mensajería. Quedamos atentos a sus preguntas."

---

# Preguntas probables

**"¿Por qué el frontend no corre en Netlify para la demo?"**
> Está publicado en Netlify y ahí funciona. Para la demostración lo servimos local porque el panel de administración le habla a los microservicios que corren en este equipo, y un sitio HTTPS llamando a localhost el navegador lo bloquea o lo pregunta. El login sigue siendo Cognito real: localhost cuenta como contexto seguro.

**"¿Por qué los consumidores no están en EC2?"**
> La instancia es t3.micro, 1 GB de RAM. Ahí tendrían que caber cuatro JVM y no entran. Además la pauta pide mostrar dos instancias del mismo consumidor y detener una, y eso se demuestra mejor con dos procesos que se pueden controlar.

**"¿Qué pasa si un mensaje llega dos veces?"**
> El productor le pone un idEvento único a cada evento, y el consumidor lleva registro de los que ya procesó. Si llega uno repetido, lo confirma sin volver a ejecutar la acción. Es necesario porque SQS estándar garantiza entrega al menos una vez, no exactamente una.
>
> Y hay una limitación que conviene reconocer: ese registro es local a cada instancia, así que si el duplicado le toca a la otra instancia no se detecta. En producción iría en una tabla compartida con el idEvento como clave única. Para el alcance de esta evaluación lo resolvimos en memoria y está documentado.

**"¿Por qué el modo de confirmación es manual?"**
> Porque con el modo automático un mensaje se confirma solo por no haber lanzado excepción, y se pierde el control sobre qué se reintenta. Con confirmación manual, ningún mensaje sale de la cola salvo que el código lo decida explícitamente, y eso nos deja distinguir entre un error que vale la pena reintentar y uno que no.

**"¿Dónde están los nombres de las colas?"**
> En el `application.yml` de cada microservicio, en ningún lugar del código Java. La fuente de verdad es el del administrador, que es el que aplica la topología sobre AWS.

**"¿Las colas las crearon a mano?"**
> No, están declaradas en código. Un solo POST a `/admin/topologia` crea el tópico, las tres colas con sus políticas, las tres DLQ, las Redrive Policy, las tres suscripciones con sus filtros y las alarmas. Es idempotente, así que reconstruir el entorno después de un reinicio del laboratorio es levantar el servicio.

**"¿Qué fue lo más difícil?"**
> Un permiso. Cuando suscribes una cola a un tópico desde la consola, AWS le agrega sola a la cola el permiso para que el tópico pueda escribirle. Por SDK no lo hace. La suscripción queda creada, se ve bien en la consola, y los mensajes simplemente nunca llegan, sin ningún error en ninguna parte. Lo resolvimos configurando la política de la cola en el mismo código que crea la suscripción.

---

# Errores a evitar

- **No leer esta guía en voz alta.** Está escrita para que entiendas; en la presentación lo dices con tus palabras.
- **No apurar el minuto 4:30.** Las dos instancias valen 15%, tanto como toda la parte de la consola.
- **No olvidar provocar la falla en el minuto 2.** Si la dejas para el final, la alarma de CloudWatch no alcanza a encenderse.
- **No decir "funciona" y pasar.** Casi todos los indicadores piden explicar o justificar. El 60% es "lo muestra pero explica poco".
- **No esconder la limitación del registro de duplicados.** Reconocerla y decir cómo se resolvería en producción suma; que la descubran preguntando, resta.
- **No pelear con la demo.** Si algo no responde en 10 segundos, pasa a la consola de AWS y sigue desde ahí.
