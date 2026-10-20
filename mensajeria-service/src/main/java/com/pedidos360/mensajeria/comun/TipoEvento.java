package com.pedidos360.mensajeria.comun;

/**
 * Tipos de evento que publica el servicio de pedidos.
 *
 * <p>Se repite en cada microservicio a proposito: es el contrato del mensaje, y
 * compartir una clase entre servicios desplegados por separado los obligaria a
 * actualizarse al mismo tiempo. Cada servicio tolera recibir un tipo que no
 * conoce (ver {@code ProcesadorEventos}).
 */
public enum TipoEvento {
    PEDIDO_CREADO,
    PEDIDO_EN_PREPARACION,
    PEDIDO_LISTO,
    PEDIDO_EN_REPARTO,
    PEDIDO_ENTREGADO,
    PEDIDO_CANCELADO
}
