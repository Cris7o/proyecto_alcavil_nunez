package com.pedidos360.backend.streaming;

import java.time.Instant;

/**
 * Evento del stream {@code pedido360-actividad}: una interaccion del cliente con
 * el menu.
 *
 * <p>La <b>clave de particion</b> es {@code sesionId}: los eventos de una misma
 * sesion quedan en orden en un shard, y como cada visita genera una sesion
 * distinta, la carga se reparte de forma pareja. Usar {@code productoId} como
 * clave concentraria los productos populares en un solo shard (hot shard).
 */
public record EventoActividad(
        int version,
        String idEvento,
        TipoActividad tipo,
        String sesionId,
        Long clienteId,
        Long productoId,
        String productoNombre,
        String categoria,
        String termino,
        Instant ocurridoEn) {

    public static final int VERSION_ACTUAL = 1;

    public String claveParticion() {
        return sesionId;
    }

    public enum TipoActividad {
        PRODUCTO_AGREGADO,
        PRODUCTO_QUITADO,
        BUSQUEDA,
        CATEGORIA_FILTRADA
    }
}
