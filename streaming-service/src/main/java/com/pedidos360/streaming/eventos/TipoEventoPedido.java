package com.pedidos360.streaming.eventos;

/**
 * Tipos de evento del ciclo de vida de un pedido. Son los mismos que publica el
 * servicio de pedidos en SNS y en el stream {@code pedido360-pedidos}.
 */
public enum TipoEventoPedido {

    PEDIDO_CREADO("PENDIENTE"),
    PEDIDO_EN_PREPARACION("EN_PREPARACION"),
    PEDIDO_LISTO("LISTO"),
    PEDIDO_EN_REPARTO("EN_REPARTO"),
    PEDIDO_ENTREGADO("ENTREGADO"),
    PEDIDO_CANCELADO("CANCELADO");

    private final String estadoResultante;

    TipoEventoPedido(String estadoResultante) {
        this.estadoResultante = estadoResultante;
    }

    /** Estado en el que queda el pedido despues de este evento. */
    public String estadoResultante() {
        return estadoResultante;
    }
}
