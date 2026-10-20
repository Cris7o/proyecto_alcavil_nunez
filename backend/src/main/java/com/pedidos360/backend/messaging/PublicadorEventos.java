package com.pedidos360.backend.messaging;

/**
 * Lo unico que la capa de negocio conoce de la mensajeria.
 *
 * <p>{@code PedidoService} depende de esta interfaz, no del SDK de AWS ni del
 * nombre del topico. Esa es la frontera que mantiene la configuracion de
 * mensajeria fuera de la logica de negocio.
 */
public interface PublicadorEventos {

    /**
     * Publica el evento en el topico. Nunca propaga una falla de infraestructura:
     * si la publicacion falla, el pedido ya quedo guardado y el error se
     * registra en el log. La alternativa seria perder el pedido por no haber
     * podido avisar de el.
     */
    void publicar(EventoPedido evento);
}
