package com.pedidos360.backend.messaging;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Publica los eventos de pedido en el topico SNS.
 *
 * <p>El atributo de mensaje {@code tipoEvento} es la pieza clave del
 * enrutamiento: es lo que leen los filtros de suscripcion para decidir a que
 * colas entregar cada evento. Sin este atributo, SNS entregaria todo a todas
 * las colas y se perderia el patron de entrega selectiva.
 */
public class SnsPublicadorEventos implements PublicadorEventos {

    private static final Logger log = LoggerFactory.getLogger(SnsPublicadorEventos.class);

    /** Nombre del atributo sobre el que operan los filtros de suscripcion. */
    public static final String ATRIBUTO_TIPO_EVENTO = "tipoEvento";

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final MensajeriaProperties propiedades;

    /** ARN resuelto una sola vez y reutilizado en las publicaciones siguientes. */
    private volatile String arnResuelto;

    public SnsPublicadorEventos(SnsClient snsClient, ObjectMapper objectMapper,
            MensajeriaProperties propiedades) {
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.propiedades = propiedades;
    }

    @Override
    public void publicar(EventoPedido evento) {
        try {
            String cuerpo = objectMapper.writeValueAsString(evento);

            PublishRequest peticion = PublishRequest.builder()
                    .topicArn(arnTopico())
                    .message(cuerpo)
                    .messageAttributes(Map.of(
                            ATRIBUTO_TIPO_EVENTO, MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(evento.tipo().name())
                                    .build()))
                    .build();

            PublishResponse respuesta = snsClient.publish(peticion);

            log.info("Evento publicado: idEvento={} tipo={} pedidoId={} messageId={}",
                    evento.idEvento(), evento.tipo(), evento.pedidoId(), respuesta.messageId());

        } catch (Exception e) {
            // El pedido ya esta guardado en la base de datos. Si no se pudo
            // avisar, se registra y se sigue: fallar aqui haria que el cliente
            // viera un error sobre un pedido que si quedo creado.
            log.error("No se pudo publicar el evento idEvento={} tipo={} pedidoId={}: {}",
                    evento.idEvento(), evento.tipo(), evento.pedidoId(), e.getMessage(), e);
        }
    }

    /**
     * Resuelve el ARN del topico. Si la configuracion ya lo trae, se usa tal
     * cual; si solo hay nombre, se obtiene con {@code CreateTopic}, que es
     * idempotente y devuelve el ARN del topico existente. Eso evita tener que
     * reconfigurar el id de cuenta en cada sesion del Learner Lab.
     */
    private String arnTopico() {
        String destino = propiedades.getTopico().destino();
        if (destino != null && destino.startsWith("arn:")) {
            return destino;
        }

        String cacheado = arnResuelto;
        if (cacheado != null) {
            return cacheado;
        }

        synchronized (this) {
            if (arnResuelto == null) {
                arnResuelto = snsClient
                        .createTopic(CreateTopicRequest.builder().name(destino).build())
                        .topicArn();
                log.info("ARN del topico '{}' resuelto como {}", destino, arnResuelto);
            }
            return arnResuelto;
        }
    }
}
