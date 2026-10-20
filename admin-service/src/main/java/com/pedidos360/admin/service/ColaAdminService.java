package com.pedidos360.admin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pedidos360.admin.config.MensajeriaProperties;
import com.pedidos360.admin.dto.ColaDto;
import com.pedidos360.admin.dto.TopicoDto;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.Subscription;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Toda la operacion de colas, topicos, suscripciones y DLQ, encapsulada.
 *
 * <p>Los controladores de este microservicio no llaman al SDK de AWS: llaman
 * metodos de esta clase. Esa frontera es la que permite cambiar como se
 * implementa una operacion sin tocar la API, y la que hace que la API se pueda
 * leer sin saber nada de SQS.
 *
 * <p>Las operaciones son idempotentes donde AWS lo permite: crear una cola o un
 * topico que ya existe devuelve el existente en lugar de fallar, para que
 * volver a declarar la topologia sea seguro.
 */
@Service
public class ColaAdminService {

    private static final Logger log = LoggerFactory.getLogger(ColaAdminService.class);

    /** Protocolo de la suscripcion: las tres colas se suscriben por SQS. */
    private static final String PROTOCOLO_SQS = "sqs";

    private static final String ATRIBUTO_FILTER_POLICY = "FilterPolicy";
    private static final String ATRIBUTO_RAW_MESSAGE_DELIVERY = "RawMessageDelivery";

    private final SqsClient sqsClient;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final MensajeriaProperties propiedades;

    public ColaAdminService(SqsClient sqsClient, SnsClient snsClient, ObjectMapper objectMapper,
            MensajeriaProperties propiedades) {
        this.sqsClient = sqsClient;
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.propiedades = propiedades;
    }

    // ------------------------------------------------------------------
    // Colas
    // ------------------------------------------------------------------

    /** Lista las colas del proyecto, reconocidas por el prefijo configurado. */
    public List<ColaDto.Resumen> listarColas() {
        return sqsClient.listQueues(peticion -> peticion.queueNamePrefix(propiedades.getPrefijo()))
                .queueUrls().stream()
                .map(url -> new ColaDto.Resumen(nombreDesdeUrl(url), url))
                .toList();
    }

    /**
     * Crea una cola con sus politicas. Si se indica una DLQ, la crea tambien y
     * las asocia con una Redrive Policy.
     */
    public ColaDto.Resumen crearCola(ColaDto.CrearCola peticion) {
        String dlqArn = null;
        if (peticion.dlqNombre() != null && !peticion.dlqNombre().isBlank()) {
            String urlDlq = crearColaSimple(peticion.dlqNombre(), Map.of(
                    QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600"));
            dlqArn = arnDeCola(urlDlq);
        }

        Map<QueueAttributeName, String> atributos = new HashMap<>();
        if (peticion.retencionSegundos() != null) {
            atributos.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD,
                    String.valueOf(peticion.retencionSegundos()));
        }
        if (peticion.visibilidadSegundos() != null) {
            atributos.put(QueueAttributeName.VISIBILITY_TIMEOUT,
                    String.valueOf(peticion.visibilidadSegundos()));
        }
        if (peticion.esperaLargaSegundos() != null) {
            atributos.put(QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS,
                    String.valueOf(peticion.esperaLargaSegundos()));
        }
        if (dlqArn != null) {
            int maxReceiveCount = peticion.maxReceiveCount() == null ? 5 : peticion.maxReceiveCount();
            atributos.put(QueueAttributeName.REDRIVE_POLICY,
                    redrivePolicy(dlqArn, maxReceiveCount));
        }

        String url = crearColaSimple(peticion.nombre(), atributos);
        log.info("Cola '{}' creada o actualizada | dlq={} | atributos={}",
                peticion.nombre(), peticion.dlqNombre(), atributos.keySet());

        return new ColaDto.Resumen(peticion.nombre(), url);
    }

    public void eliminarCola(String nombre) {
        String url = urlDeCola(nombre);
        sqsClient.deleteQueue(peticion -> peticion.queueUrl(url));
        log.warn("Cola '{}' eliminada", nombre);
    }

    /** Estado operativo de una cola, incluido el de su DLQ si tiene una. */
    public ColaDto.Estado estadoDeCola(String nombre) {
        String url = urlDeCola(nombre);
        Map<QueueAttributeName, String> atributos = atributosDeCola(url);

        String redrivePolicy = atributos.get(QueueAttributeName.REDRIVE_POLICY);
        ColaDto.EstadoDlq estadoDlq = estadoDeDlqAsociada(redrivePolicy);

        return new ColaDto.Estado(
                nombre,
                url,
                entero(atributos, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES),
                entero(atributos, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE),
                entero(atributos, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED),
                entero(atributos, QueueAttributeName.MESSAGE_RETENTION_PERIOD),
                entero(atributos, QueueAttributeName.VISIBILITY_TIMEOUT),
                entero(atributos, QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS),
                redrivePolicy,
                estadoDlq);
    }

    /** Estado de las tres colas declaradas, de una sola llamada. */
    public List<ColaDto.Estado> estadoDeColasDeclaradas() {
        return propiedades.getColas().stream()
                .map(cola -> estadoDeCola(cola.getNombre()))
                .toList();
    }

    /**
     * Vacia una cola. Es la operacion de limpieza de ultimo recurso: SQS solo
     * permite una purga cada 60 segundos y el borrado no es instantaneo.
     */
    public void purgarCola(String nombre) {
        String url = urlDeCola(nombre);
        sqsClient.purgeQueue(peticion -> peticion.queueUrl(url));
        log.warn("Cola '{}' purgada: todos sus mensajes fueron descartados", nombre);
    }

    // ------------------------------------------------------------------
    // Topicos
    // ------------------------------------------------------------------

    public List<TopicoDto.Resumen> listarTopicos() {
        return snsClient.listTopicsPaginator().topics().stream()
                .map(topico -> new TopicoDto.Resumen(nombreDesdeArn(topico.topicArn()), topico.topicArn()))
                .filter(resumen -> resumen.nombre().startsWith(propiedades.getPrefijo()))
                .toList();
    }

    /** Crea un topico. {@code CreateTopic} es idempotente. */
    public TopicoDto.Resumen crearTopico(String nombre) {
        String arn = snsClient.createTopic(peticion -> peticion.name(nombre)).topicArn();
        log.info("Topico '{}' creado o ya existente | arn={}", nombre, arn);
        return new TopicoDto.Resumen(nombre, arn);
    }

    public void eliminarTopico(String nombre) {
        String arn = arnDeTopico(nombre);
        snsClient.deleteTopic(peticion -> peticion.topicArn(arn));
        log.warn("Topico '{}' eliminado", nombre);
    }

    // ------------------------------------------------------------------
    // Suscripciones
    // ------------------------------------------------------------------

    public List<TopicoDto.ResumenSuscripcion> listarSuscripciones(String nombreTopico) {
        String arnTopico = arnDeTopico(nombreTopico);

        List<TopicoDto.ResumenSuscripcion> resultado = new ArrayList<>();
        for (Subscription suscripcion : snsClient
                .listSubscriptionsByTopicPaginator(peticion -> peticion.topicArn(arnTopico))
                .subscriptions()) {

            Map<String, String> atributos = snsClient
                    .getSubscriptionAttributes(p -> p.subscriptionArn(suscripcion.subscriptionArn()))
                    .attributes();

            String filtro = atributos.get(ATRIBUTO_FILTER_POLICY);
            resultado.add(new TopicoDto.ResumenSuscripcion(
                    suscripcion.subscriptionArn(),
                    suscripcion.protocol(),
                    nombreDesdeArn(suscripcion.endpoint()),
                    patronDe(filtro),
                    filtro == null ? "(sin filtro)" : filtro,
                    atributos.getOrDefault(ATRIBUTO_RAW_MESSAGE_DELIVERY, "false")));
        }
        return resultado;
    }

    /**
     * Suscribe una cola a un topico.
     *
     * <p>Antes de suscribir, le concede al topico permiso para escribir en la
     * cola. Es un paso que la consola de AWS hace sola y el SDK no: sin ese
     * permiso la suscripcion queda creada pero los mensajes nunca llegan, y es
     * dificil de diagnosticar porque no hay ningun error visible.
     */
    public TopicoDto.ResumenSuscripcion suscribir(TopicoDto.CrearSuscripcion peticion) {
        String arnTopico = arnDeTopico(peticion.topicoNombre());
        String urlCola = urlDeCola(peticion.colaNombre());
        String arnCola = arnDeCola(urlCola);

        permitirEscrituraDesdeTopico(urlCola, arnCola, arnTopico);

        Map<String, String> atributos = new HashMap<>();
        boolean raw = peticion.rawMessageDelivery() == null || peticion.rawMessageDelivery();
        atributos.put(ATRIBUTO_RAW_MESSAGE_DELIVERY, String.valueOf(raw));

        boolean tieneFiltro = peticion.filtro() != null && !peticion.filtro().isEmpty();
        if (tieneFiltro) {
            atributos.put(ATRIBUTO_FILTER_POLICY, aJson(peticion.filtro()));
        }

        String arnSuscripcion = snsClient.subscribe(SubscribeRequest.builder()
                .topicArn(arnTopico)
                .protocol(PROTOCOLO_SQS)
                .endpoint(arnCola)
                .attributes(atributos)
                .returnSubscriptionArn(true)
                .build())
                .subscriptionArn();

        log.info("Suscripcion '{}' -> cola '{}' | patron={} | filtro={}",
                peticion.topicoNombre(), peticion.colaNombre(),
                tieneFiltro ? "entrega selectiva" : "fanout",
                tieneFiltro ? peticion.filtro() : "(sin filtro)");

        return new TopicoDto.ResumenSuscripcion(
                arnSuscripcion,
                PROTOCOLO_SQS,
                peticion.colaNombre(),
                tieneFiltro ? "entrega selectiva" : "fanout",
                tieneFiltro ? aJson(peticion.filtro()) : "(sin filtro)",
                String.valueOf(raw));
    }

    public void eliminarSuscripcion(String arnSuscripcion) {
        snsClient.unsubscribe(peticion -> peticion.subscriptionArn(arnSuscripcion));
        log.warn("Suscripcion '{}' eliminada", arnSuscripcion);
    }

    /**
     * Cambia el filtro de una suscripcion en caliente. Un filtro vacio la
     * convierte en fanout; con contenido, en entrega selectiva.
     */
    public void actualizarFiltro(String arnSuscripcion, Map<String, List<String>> filtro) {
        boolean tieneFiltro = filtro != null && !filtro.isEmpty();

        snsClient.setSubscriptionAttributes(peticion -> peticion
                .subscriptionArn(arnSuscripcion)
                .attributeName(ATRIBUTO_FILTER_POLICY)
                .attributeValue(tieneFiltro ? aJson(filtro) : ""));

        log.info("Filtro de la suscripcion '{}' actualizado | patron={}",
                arnSuscripcion, tieneFiltro ? "entrega selectiva" : "fanout");
    }

    // ------------------------------------------------------------------
    // Colas de mensajes muertos
    // ------------------------------------------------------------------

    /**
     * Lee los mensajes de una DLQ sin consumirlos.
     *
     * <p>Devuelve la visibilidad a cero inmediatamente despues de leer, para que
     * los mensajes sigan contando en la consola y la alarma de CloudWatch no se
     * apague mientras se los inspecciona.
     */
    public List<ColaDto.MensajeEnDlq> inspeccionarDlq(String nombreDlq, int maximo) {
        String url = urlDeCola(nombreDlq);
        List<Message> mensajes = recibir(url, maximo);

        List<ColaDto.MensajeEnDlq> resultado = mensajes.stream().map(this::aMensajeEnDlq).toList();

        // Devolver la visibilidad: inspeccionar no debe esconder los mensajes.
        mensajes.forEach(mensaje -> sqsClient.changeMessageVisibility(peticion -> peticion
                .queueUrl(url)
                .receiptHandle(mensaje.receiptHandle())
                .visibilityTimeout(0)));

        log.info("DLQ '{}' inspeccionada | {} mensajes leidos sin consumir", nombreDlq, resultado.size());
        return resultado;
    }

    /**
     * Devuelve los mensajes de una DLQ a su cola de origen para reintentarlos.
     *
     * <p>Registra en el log el id del evento, el intento y la causa de cada
     * mensaje antes de devolverlo, de modo que quede asentado que se reproceso y
     * por que habia fallado.
     *
     * <p>El mensaje se borra de la DLQ solo despues de que la cola destino
     * confirmo la recepcion: si el envio falla, el mensaje se queda en la DLQ.
     */
    public ColaDto.ResultadoReproceso reprocesarDlq(String nombreDlq, String nombreDestino, int maximo) {
        String urlDlq = urlDeCola(nombreDlq);
        String urlDestino = urlDeCola(nombreDestino);

        List<ColaDto.MensajeEnDlq> reprocesados = new ArrayList<>();

        for (Message mensaje : recibir(urlDlq, maximo)) {
            ColaDto.MensajeEnDlq detalle = aMensajeEnDlq(mensaje);

            log.info("Reprocesando desde DLQ '{}' hacia '{}' | idEvento={} | intento={} | causa={}",
                    nombreDlq, nombreDestino, detalle.idEvento(), detalle.intento(), detalle.causa());

            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(urlDestino)
                    .messageBody(mensaje.body())
                    .build());

            sqsClient.deleteMessage(peticion -> peticion
                    .queueUrl(urlDlq)
                    .receiptHandle(mensaje.receiptHandle()));

            reprocesados.add(detalle);
        }

        log.info("Reproceso terminado | DLQ '{}' -> '{}' | {} mensajes",
                nombreDlq, nombreDestino, reprocesados.size());

        return new ColaDto.ResultadoReproceso(nombreDlq, nombreDestino, reprocesados.size(), reprocesados);
    }

    // ------------------------------------------------------------------
    // Utilidades internas
    // ------------------------------------------------------------------

    private String crearColaSimple(String nombre, Map<QueueAttributeName, String> atributos) {
        try {
            // Si ya existe, se actualizan sus atributos en lugar de fallar: asi
            // volver a declarar la topologia es seguro y repetible.
            String url = urlDeCola(nombre);
            if (!atributos.isEmpty()) {
                sqsClient.setQueueAttributes(peticion -> peticion.queueUrl(url).attributes(atributos));
                log.debug("Atributos de la cola '{}' actualizados: {}", nombre, atributos.keySet());
            }
            return url;

        } catch (NoSuchElementException e) {
            return sqsClient.createQueue(CreateQueueRequest.builder()
                    .queueName(nombre)
                    .attributes(atributos)
                    .build())
                    .queueUrl();
        }
    }

    /**
     * Concede al topico permiso para escribir en la cola. La condicion
     * {@code aws:SourceArn} limita el permiso a ese topico y no a cualquiera.
     */
    private void permitirEscrituraDesdeTopico(String urlCola, String arnCola, String arnTopico) {
        String politica = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Sid": "PermitirEnvioDesdeTopicoPedido360",
                      "Effect": "Allow",
                      "Principal": { "Service": "sns.amazonaws.com" },
                      "Action": "sqs:SendMessage",
                      "Resource": "%s",
                      "Condition": { "ArnEquals": { "aws:SourceArn": "%s" } }
                    }
                  ]
                }
                """.formatted(arnCola, arnTopico);

        sqsClient.setQueueAttributes(peticion -> peticion
                .queueUrl(urlCola)
                .attributes(Map.of(QueueAttributeName.POLICY, politica)));
    }

    private List<Message> recibir(String url, int maximo) {
        int aPedir = Math.max(1, Math.min(maximo, 10));
        return sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(url)
                .maxNumberOfMessages(aPedir)
                .messageAttributeNames("All")
                .waitTimeSeconds(1)
                .visibilityTimeout(30)
                .build())
                .messages();
    }

    private ColaDto.MensajeEnDlq aMensajeEnDlq(Message mensaje) {
        Map<String, MessageAttributeValue> atributos = mensaje.messageAttributes();
        return new ColaDto.MensajeEnDlq(
                mensaje.messageId(),
                textoDe(atributos, "idEvento"),
                textoDe(atributos, "intento"),
                textoDe(atributos, "causa"),
                textoDe(atributos, "colaOrigen"),
                mensaje.body());
    }

    private String textoDe(Map<String, MessageAttributeValue> atributos, String clave) {
        MessageAttributeValue valor = atributos.get(clave);
        if (valor == null) {
            // Mensaje movido por la Redrive Policy de AWS: no trae la causa,
            // porque AWS no la conoce. Se deja constancia de eso.
            return "(movido por la Redrive Policy, sin dato)";
        }
        return valor.stringValue();
    }

    private ColaDto.EstadoDlq estadoDeDlqAsociada(String redrivePolicy) {
        if (redrivePolicy == null || redrivePolicy.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> politica = objectMapper.readValue(redrivePolicy, Map.class);
            String arnDlq = String.valueOf(politica.get("deadLetterTargetArn"));
            String nombreDlq = nombreDesdeArn(arnDlq);

            String urlDlq = urlDeCola(nombreDlq);
            Map<QueueAttributeName, String> atributos = atributosDeCola(urlDlq);

            return new ColaDto.EstadoDlq(
                    nombreDlq,
                    entero(atributos, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES),
                    entero(atributos, QueueAttributeName.MESSAGE_RETENTION_PERIOD));

        } catch (Exception e) {
            log.warn("No se pudo leer el estado de la DLQ desde la Redrive Policy: {}", e.getMessage());
            return null;
        }
    }

    private Map<QueueAttributeName, String> atributosDeCola(String url) {
        return sqsClient.getQueueAttributes(peticion -> peticion
                .queueUrl(url)
                .attributeNames(QueueAttributeName.ALL))
                .attributes();
    }

    public String urlDeCola(String nombre) {
        try {
            return sqsClient.getQueueUrl(peticion -> peticion.queueName(nombre)).queueUrl();
        } catch (QueueDoesNotExistException e) {
            throw new NoSuchElementException("No existe la cola '" + nombre + "'");
        }
    }

    private String arnDeCola(String url) {
        return sqsClient.getQueueAttributes(peticion -> peticion
                .queueUrl(url)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
    }

    /** Resuelve el ARN del topico por nombre. {@code CreateTopic} es idempotente. */
    public String arnDeTopico(String nombre) {
        return snsClient.createTopic(peticion -> peticion.name(nombre)).topicArn();
    }

    private String redrivePolicy(String arnDlq, int maxReceiveCount) {
        return """
                {"deadLetterTargetArn":"%s","maxReceiveCount":"%d"}"""
                .formatted(arnDlq, maxReceiveCount);
    }

    private String aJson(Map<String, List<String>> filtro) {
        return objectMapper.writeValueAsString(filtro);
    }

    private String patronDe(String filtro) {
        return (filtro == null || filtro.isBlank()) ? "fanout" : "entrega selectiva";
    }

    private int entero(Map<QueueAttributeName, String> atributos, QueueAttributeName clave) {
        String valor = atributos.get(clave);
        if (valor == null) {
            return 0;
        }
        try {
            return Integer.parseInt(valor);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String nombreDesdeUrl(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private String nombreDesdeArn(String arn) {
        return arn == null ? "" : arn.substring(arn.lastIndexOf(':') + 1);
    }
}
