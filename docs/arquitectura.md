# Arquitectura y recorrido de un mensaje

Los dos diagramas que pide la pauta como opcionales.

---

## 1. Arquitectura general

```
                            ┌────────────────┐
                            │    COGNITO     │
                            │  User Pool     │  emite el JWT con el grupo
                            │  4 grupos      │  del usuario
                            └────────┬───────┘
                                     │
   ┌──────────────┐          ┌───────▼──────────┐          ┌──────────────────────┐
   │   FRONTEND   │   JWT    │   API GATEWAY    │          │       BACKEND        │
   │   React +    ├─────────►│  (API Manager)   ├─────────►│  servicio de pedidos │
   │   Amplify    │          │  autorizador JWT │          │       en EC2         │
   │              │          └──────────────────┘          │                      │
   │  Netlify     │                                        │  valida el JWT otra  │
   │  localhost   │                                        │  vez y evalúa el rol │
   └──────┬───────┘                                        └───┬──────────────┬───┘
          │                                                    │              │
          │                                              ┌─────▼─────┐  ┌─────▼──────┐
          │                                              │    RDS    │  │    SNS     │
          │                                              │   MySQL   │  │  tópico    │
          │                                              │  sin IP   │  │  eventos   │
          │                                              │  pública  │  │  de pedido │
          │                                              └───────────┘  └─────┬──────┘
          │                                                                   │
          │                   ┌───────────────────┬───────────────────────────┴───┐
          │                   │ filtro            │ filtro                        │ sin filtro
          │                   │ CREADO, LISTO,    │ CREADO, ENTREGADO             │ (fanout)
          │                   │ ENTREGADO,        │                               │
          │                   │ CANCELADO         │                               │
          │                   ▼                   ▼                               ▼
          │         ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────────┐
          │         │  NOTIFICACIONES  │ │   DOCUMENTOS     │ │     AUDITORÍA        │
          │         │  retención 4 d   │ │  retención 14 d  │ │   retención 7 d      │
          │         │  visibilidad 30s │ │  visibilidad 60s │ │   visibilidad 30s    │
          │         └────────┬─────────┘ └────────┬─────────┘ └──────────┬───────────┘
          │                  │ Redrive            │ Redrive              │ Redrive
          │                  │ maxReceive 5       │ maxReceive 8         │ maxReceive 5
          │         ┌────────▼─────────┐ ┌────────▼─────────┐ ┌──────────▼───────────┐
          │         │       DLQ        │ │       DLQ        │ │        DLQ           │
          │         │  retención 14 d  │ │  retención 14 d  │ │   retención 14 d     │
          │         └────────┬─────────┘ └────────┬─────────┘ └──────────┬───────────┘
          │                  │                    │                      │
          │                  └────────────────────┼──────────────────────┘
          │                                       │
          │                              ┌────────▼──────────┐
          │                              │    CLOUDWATCH     │
          │                              │  1 alarma por DLQ │
          │                              │  >= 1 mensaje     │
          │                              └───────────────────┘
          │
          │         ┌─────────────────────────────────────────────┐
          │         │          MENSAJERIA-SERVICE  ×2             │
          │         │  consumidores, agrupados por dominio        │
          │         │                                             │
          │         │   notificaciones/   documentos/   auditoria/│
          │         │          └──────────────┴────────────┘      │
          │         │                   ProcesadorEventos         │
          │         │       (confirmación y manejo de errores)    │
          │         └──────────────────────┬──────────────────────┘
          │                                │ pausar / reanudar
          │                                │ (HTTP interno)
          │         ┌──────────────────────┴──────────────────────┐
          └────────►│              ADMIN-SERVICE                  │
         panel de   │  colas · tópicos · suscripciones · DLQ      │
         mensajería │  consumidores · monitoreo · topología       │
                    └─────────────────────────────────────────────┘
```

### Dos decisiones de diseño que se ven en el diagrama

**El navegador nunca le habla directo al backend.** Todo entra por API Gateway, que valida el JWT antes de dejar pasar nada. Y la base de datos no tiene IP pública: solo es alcanzable desde dentro de la red privada.

**El backend no conoce las colas.** Publica en un tópico y se desentiende. Por eso se pueden agregar o quitar consumidores sin tocar ni redesplegar el servicio de pedidos — que es el punto de desacoplar con mensajería.

### Dónde corre cada cosa

| Pieza | Dónde | Por qué |
|---|---|---|
| Cognito, SNS, SQS, CloudWatch | AWS | Son servicios de Amazon, no programas nuestros |
| `backend` | EC2 | Tiene que estar donde API Gateway lo busca |
| Base de datos | RDS | — |
| `mensajeria-service` ×2, `admin-service` | El equipo de la presentación | La instancia es `t3.micro`: 1 GB de RAM no alcanza para cuatro JVM |
| `frontend` | Netlify (publicado) y local (para la demo) | El panel le habla al administrador, que corre en el equipo |

---

## 2. Recorrido de un mensaje

Desde que se publica hasta que se confirma, se reintenta o termina en la DLQ.

```
   El cliente confirma un pedido
              │
              ▼
   ┌─────────────────────────┐
   │ BACKEND                 │
   │ guarda el pedido en RDS │
   │ y publica el evento     │   atributo tipoEvento = PEDIDO_CREADO
   └───────────┬─────────────┘
               │
               ▼
   ┌─────────────────────────┐
   │ SNS evalúa los filtros  │
   │ de cada suscripción     │
   └───────────┬─────────────┘
               │
       ┌───────┴────────┐
       │                │
   ¿coincide?       ¿no coincide?
       │                │
       ▼                ▼
   entra a la       SE DESCARTA
   cola SQS         nunca toca la cola
       │
       ▼
   ┌─────────────────────────────────────────┐
   │ Un consumidor lo toma                   │
   │ el mensaje queda INVISIBLE para los     │
   │ demás durante el visibility timeout     │
   └───────────┬─────────────────────────────┘
               │
               ▼
   ┌─────────────────────────┐
   │ ¿El cuerpo es legible?  │
   └───────────┬─────────────┘
          no   │   sí
       ┌───────┴───────┐
       ▼               ▼
   ┌───────┐   ┌─────────────────────────────┐
   │  DLQ  │   │ ¿Este idEvento ya se procesó?│
   │   +   │   └───────────┬─────────────────┘
   │ causa │          sí   │   no
   │   +   │       ┌───────┴───────┐
   │confirma│      ▼               ▼
   └───────┘   ┌────────┐   ┌──────────────────┐
               │CONFIRMA│   │ Ejecuta la acción│
               │sin     │   │ del dominio      │
               │repetir │   └───────┬──────────┘
               └────────┘           │
                            ┌───────┴────────┐
                       ¿salió bien?     ¿falló?
                            │               │
                            ▼               ▼
                     ┌────────────┐  ┌──────────────────────┐
                     │ marca el   │  │ ¿Qué tipo de error?  │
                     │ idEvento   │  └──────┬───────────────┘
                     │ y CONFIRMA │         │
                     └────────────┘   ┌─────┴──────────┐
                                      │                │
                              no recuperable      recuperable
                                      │                │
                                      ▼                ▼
                               ┌────────────┐  ┌────────────────────┐
                               │   DLQ      │  │ ¿quedan intentos?  │
                               │  + causa   │  └────┬───────────────┘
                               │ + CONFIRMA │       │
                               │            │  sí   │   no
                               │ (reintentar│ ┌─────┴──────┐
                               │  daría lo  │ ▼            ▼
                               │  mismo)    │ NO CONFIRMA  ┌────────────┐
                               └────────────┘ │            │   DLQ      │
                                               │            │  + causa   │
                                      SQS lo vuelve a      │ + CONFIRMA │
                                      entregar al expirar  └────────────┘
                                      el visibility timeout
                                               │
                                               └──► vuelve arriba
```

### Lo que hay que entender de este diagrama

**Confirmar = borrar de la cola.** Es la única forma en que un mensaje desaparece de SQS. Si nadie confirma, el mensaje vuelve. Por eso *no confirmar* es la manera correcta de pedir un reintento, y por eso pausar un consumidor no pierde nada.

**Hay dos caminos a la DLQ, y son distintos a propósito:**

| | No recuperable | Recuperable |
|---|---|---|
| Ejemplo | el mensaje no es JSON válido, falta el correo del cliente | el proveedor de correo no responde, el disco está ocupado |
| Qué se hace | a la DLQ en el primer intento | se reintenta hasta agotar los intentos |
| Por qué | reintentar daría exactamente el mismo resultado | puede que la próxima vez funcione |

**Nosotros derivamos a la DLQ antes que AWS.** La Redrive Policy de AWS también mueve mensajes a la DLQ, pero **sin explicar por qué fallaron**. Derivando nosotros, el mensaje llega acompañado de su causa, el intento y la cola de origen, como atributos.

Por eso el `maxReceiveCount` de la Redrive Policy está deliberadamente **por encima** de los reintentos del consumidor: el consumidor deriva primero con la causa, y la Redrive Policy queda como red de seguridad para el caso en que el proceso muera antes de alcanzar a hacerlo.

| Cola | Reintentos del consumidor | maxReceiveCount de AWS |
|---|---|---|
| notificaciones | 3 | 5 |
| documentos | 5 | 8 |
| auditoría | 3 | 5 |

**No hay ningún consumidor leyendo las DLQ.** Un consumidor sobre la DLQ borraría los mensajes al leerlos, y entonces no quedarían visibles en la consola ni dispararían la alarma de CloudWatch. Las DLQ se inspeccionan y se reprocesan desde el administrador, bajo demanda.

---

## 3. Los dos patrones de enrutamiento

```
                 TÓPICO  pedido360-eventos-pedido
                             │
   ┌─────────────────────────┼─────────────────────────┐
   │                         │                         │
   │ FILTRO                  │ FILTRO                  │ SIN FILTRO
   │ CREADO                  │ CREADO                  │
   │ LISTO                   │ ENTREGADO               │
   │ ENTREGADO               │                         │
   │ CANCELADO               │                         │
   ▼                         ▼                         ▼
NOTIFICACIONES           DOCUMENTOS                AUDITORÍA
```

Qué pasa con cada evento:

| Evento publicado | notificaciones | documentos | auditoría |
|---|:---:|:---:|:---:|
| `PEDIDO_CREADO` | ✔ | ✔ | ✔ |
| `PEDIDO_EN_PREPARACION` | — | — | ✔ |
| `PEDIDO_LISTO` | ✔ | — | ✔ |
| `PEDIDO_EN_REPARTO` | — | — | ✔ |
| `PEDIDO_ENTREGADO` | ✔ | ✔ | ✔ |
| `PEDIDO_CANCELADO` | ✔ | — | ✔ |

**Fanout** es la fila de auditoría: recibe absolutamente todo, porque no tiene filtro. Un `PEDIDO_CREADO` llega a las tres colas de una sola publicación.

**Entrega selectiva** son las otras dos columnas: SNS evalúa el atributo `tipoEvento` del mensaje contra el filtro de cada suscripción, y solo entrega donde coincide. Un `PEDIDO_EN_PREPARACION` se descarta en SNS para notificaciones y documentos — **nunca llega a esas colas**, no es que el consumidor lo ignore después.

Esa distinción importa: filtrar en SNS no cuesta nada. Filtrar en el consumidor significaría pagar por almacenar, entregar y procesar mensajes que no interesan.

### Por qué cada cola está separada

| Cola | Qué hace | Por qué merece su propia cola |
|---|---|---|
| notificaciones | avisar al cliente | Si el proveedor de correo se cae, no tiene por qué atrasar nada más |
| documentos | comanda de cocina y boleta | Es más lento y más crítico: necesita más reintentos y más retención |
| auditoría | bitácora completa | No puede perder eventos, pero sí puede atrasarse |

Si fueran una sola cola, un problema del proveedor de correo atrasaría la bitácora y las boletas.
