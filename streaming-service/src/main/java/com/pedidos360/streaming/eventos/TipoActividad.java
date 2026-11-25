package com.pedidos360.streaming.eventos;

/** Interacciones del cliente con el menu que viajan por el stream de actividad. */
public enum TipoActividad {
    PRODUCTO_AGREGADO,
    PRODUCTO_QUITADO,
    BUSQUEDA,
    CATEGORIA_FILTRADA
}
