package com.pedidos360.admin.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pedidos360.admin.config.MensajeriaProperties;
import com.pedidos360.admin.dto.TopicoDto;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Publica eventos sinteticos en el topico, para demostrar el enrutamiento.
 *
 * <p>Sirve para tres cosas que serian incomodas de mostrar creando pedidos
 * reales desde el frontend:
 *
 * <ul>
 * <li>Fanout: publicar un {@code PEDIDO_CREADO} y ver que llega a las tres colas.
 * <li>Entrega selectiva: publicar un {@code PEDIDO_EN_PREPARACION} y ver que
 * llega solo a auditoria, porque los filtros de las otras dos lo descartan.
 * <li>Duplicados: publicar dos veces el mismo {@code idEvento} y ver que el
 * consumidor procesa uno y descarta el otro.
 * </ul>
 *
 * <p>El cuerpo que arma respeta el mismo contrato que publica el servicio de
 * pedidos, para que los consumidores no distingan entre un evento real y uno de
 * prueba.
 */
@Service
public class PublicacionPruebaService {

    private static final Logger log = LoggerFactory.getLogger(PublicacionPruebaService.class);

    /** Atributo sobre el que operan los filtros de suscripcion. */
    private static final String ATRIBUTO_TIPO_EVENTO = "tipoEvento";

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final ColaAdminService colaAdminService;
    private final MensajeriaProperties propiedades;

    public PublicacionPruebaService(SnsClient snsClient, ObjectMapper objectMapper,
            ColaAdminService colaAdminService, MensajeriaProperties propiedades) {
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.colaAdminService = colaAdminService;
        this.propiedades = propiedades;
    }

    public TopicoDto.ResultadoPublicacion publicar(TopicoDto.EventoPrueba peticion) {
        String arnTopico = colaAdminService.arnDeTopico(propiedades.getTopico().getNombre());

        String idEvento = (peticion.idEvento() == null || peticion.idEvento().isBlank())
                ? UUID.randomUUID().toString()
                : peticion.idEvento();

        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("idEvento", idEvento);
        evento.put("tipo", peticion.tipoEvento());
        evento.put("pedidoId", peticion.pedidoId() == null ? 0L : peticion.pedidoId());
        evento.put("estadoAnterior", null);
        evento.put("estadoNuevo", estadoDesdeTipo(peticion.tipoEvento()));
        evento.put("clienteId", 0L);
        evento.put("clienteNombre",
                peticion.clienteNombre() == null ? "Cliente de prueba" : peticion.clienteNombre());
        evento.put("clienteEmail",
                peticion.clienteEmail() == null ? "prueba@pedido360.cl" : peticion.clienteEmail());
        evento.put("total", BigDecimal.ZERO);
        evento.put("ocurridoEn", Instant.now().toString());

        String cuerpo = objectMapper.writeValueAsString(evento);

        String messageId = snsClient.publish(PublishRequest.builder()
                .topicArn(arnTopico)
                .message(cuerpo)
                .messageAttributes(Map.of(
                        ATRIBUTO_TIPO_EVENTO, MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(peticion.tipoEvento())
                                .build()))
                .build())
                .messageId();

        log.info("Evento de prueba publicado | tipo={} | idEvento={} | messageId={}",
                peticion.tipoEvento(), idEvento, messageId);

        return new TopicoDto.ResultadoPublicacion(
                arnTopico, messageId, peticion.tipoEvento(), idEvento, cuerpo);
    }

    /** El estado al que corresponde cada tipo, para que el cuerpo sea coherente. */
    private String estadoDesdeTipo(String tipoEvento) {
        return switch (tipoEvento) {
            case "PEDIDO_CREADO" -> "PENDIENTE";
            case "PEDIDO_EN_PREPARACION" -> "EN_PREPARACION";
            case "PEDIDO_LISTO" -> "LISTO";
            case "PEDIDO_EN_REPARTO" -> "EN_REPARTO";
            case "PEDIDO_ENTREGADO" -> "ENTREGADO";
            case "PEDIDO_CANCELADO" -> "CANCELADO";
            default -> "DESCONOCIDO";
        };
    }
}
