package com.pedidos360.backend.messaging;

import com.pedidos360.backend.model.EstadoPedido;

/**
 * Tipos de evento que publica el servicio de pedidos en el topico SNS.
 *
 * <p>El nombre del tipo viaja como atributo de mensaje {@code tipoEvento}, y es
 * sobre ese atributo que operan los filtros de suscripcion: las colas de
 * notificaciones y documentos solo reciben un subconjunto, mientras auditoria
 * recibe todos.
 */
public enum TipoEvento {

    PEDIDO_CREADO,
    PEDIDO_EN_PREPARACION,
    PEDIDO_LISTO,
    PEDIDO_EN_REPARTO,
    PEDIDO_ENTREGADO,
    PEDIDO_CANCELADO;

    /**
     * Traduce el estado al que acaba de llegar un pedido en el tipo de evento
     * que corresponde publicar.
     */
    public static TipoEvento desdeEstado(EstadoPedido estado) {
        return switch (estado) {
            case PENDIENTE -> PEDIDO_CREADO;
            case EN_PREPARACION -> PEDIDO_EN_PREPARACION;
            case LISTO -> PEDIDO_LISTO;
            case EN_REPARTO -> PEDIDO_EN_REPARTO;
            case ENTREGADO -> PEDIDO_ENTREGADO;
            case CANCELADO -> PEDIDO_CANCELADO;
        };
    }
}
