package com.pedidos360.mensajeria.notificaciones;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pedidos360.mensajeria.comun.ErrorNoRecuperableException;
import com.pedidos360.mensajeria.comun.EventoPedido;

/**
 * Dominio de notificaciones al cliente.
 *
 * <p>No conoce SQS, ni SNS, ni nombres de colas: recibe un evento ya
 * deserializado y decide que avisar. Esa es la separacion que permite probar
 * esta clase sin nube.
 */
@Service
public class NotificacionService {

    private static final Logger log = LoggerFactory.getLogger(NotificacionService.class);

    public void notificar(EventoPedido evento) {
        // Sin destinatario no hay nada que reintentar: es un error permanente.
        if (evento.clienteEmail() == null || evento.clienteEmail().isBlank()) {
            throw new ErrorNoRecuperableException(
                    "El evento del pedido " + evento.pedidoId() + " no trae correo del cliente");
        }

        log.info("NOTIFICACION a {} <{}> | pedido #{} | {}",
                evento.clienteNombre(), evento.clienteEmail(), evento.pedidoId(),
                mensajeParaCliente(evento));
    }

    private String mensajeParaCliente(EventoPedido evento) {
        return switch (evento.tipo()) {
            case PEDIDO_CREADO -> "Recibimos tu pedido, ya esta en la cola de la cocina.";
            case PEDIDO_LISTO -> "Tu pedido esta listo y sale a reparto en breve.";
            case PEDIDO_ENTREGADO -> "Tu pedido fue entregado. Buen provecho.";
            case PEDIDO_CANCELADO -> "Tu pedido fue cancelado. No se te cobrara nada.";
            // Los estados intermedios no llegan aqui: el filtro de suscripcion
            // los excluye. Si llegara uno, se ignora sin fallar.
            default -> "Tu pedido cambio de estado a " + evento.estadoNuevo() + ".";
        };
    }
}
