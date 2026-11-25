package com.pedidos360.streaming;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pedidos360.streaming.eventos.EventoActividad;
import com.pedidos360.streaming.eventos.EventoPedidoStream;
import com.pedidos360.streaming.eventos.ItemEventoPedido;
import com.pedidos360.streaming.eventos.TipoActividad;
import com.pedidos360.streaming.eventos.TipoEventoPedido;

/** Eventos de ejemplo para las pruebas. */
public final class Eventos {

    /** 2026-11-24 15:30 en Santiago (UTC-3 en verano). */
    public static final Instant MEDIA_TARDE = Instant.parse("2026-11-24T18:30:00Z");

    private Eventos() {
    }

    public static EventoPedidoStream pedidoCreado(long pedidoId) {
        List<ItemEventoPedido> items = List.of(
                new ItemEventoPedido(10L, "Hamburguesa Clasica", 2, new BigDecimal("5990")),
                new ItemEventoPedido(20L, "Papas Medianas", 1, new BigDecimal("2490")));
        return new EventoPedidoStream(1, UUID.randomUUID().toString(), TipoEventoPedido.PEDIDO_CREADO, pedidoId,
                null, "PENDIENTE", 7L, new BigDecimal("14470"), 3, items, MEDIA_TARDE, "pedidos-service");
    }

    public static EventoPedidoStream transicion(long pedidoId, TipoEventoPedido tipo, String desde, Instant cuando) {
        return new EventoPedidoStream(1, UUID.randomUUID().toString(), tipo, pedidoId, desde,
                tipo.estadoResultante(), 7L, new BigDecimal("14470"), 3, List.of(), cuando, "pedidos-service");
    }

    public static EventoActividad actividad(TipoActividad tipo, Long productoId, String termino) {
        return new EventoActividad(1, UUID.randomUUID().toString(), tipo, "sesion-1", 7L, productoId,
                productoId == null ? null : "Producto " + productoId, null, termino, MEDIA_TARDE);
    }
}
