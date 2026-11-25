package com.pedidos360.streaming.kcl;

import com.pedidos360.streaming.eventos.EventoFallido;

/** Destino de los eventos que una aplicacion consumidora no pudo procesar. */
public interface PublicadorFallidos {

    /**
     * Publica el evento en el stream de fallidos.
     *
     * @return {@code true} si quedo publicado. Con {@code false} el contenido
     *         ya quedo completo en el log de error (ultimo recurso).
     */
    boolean publicar(EventoFallido fallido);
}
