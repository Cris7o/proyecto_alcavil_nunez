package com.pedidos360.backend.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publicador usado en desarrollo local, cuando no hay credenciales de AWS.
 *
 * <p>Deja el evento en el log en lugar de enviarlo, de modo que el flujo de
 * pedidos se puede probar con H2 y sin nube. Se activa con
 * {@code pedido360.mensajeria.habilitada=false}.
 */
public class PublicadorEventosRegistrado implements PublicadorEventos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorEventosRegistrado.class);

    @Override
    public void publicar(EventoPedido evento) {
        log.info("Mensajeria deshabilitada, evento no publicado: idEvento={} tipo={} pedidoId={}",
                evento.idEvento(), evento.tipo(), evento.pedidoId());
    }
}
