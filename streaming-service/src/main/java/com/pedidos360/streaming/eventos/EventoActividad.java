package com.pedidos360.streaming.eventos;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Evento del stream {@code pedido360-actividad}: una interaccion del cliente con
 * el menu (agregar o quitar del carrito, buscar, filtrar).
 *
 * <p>Clave de particion: {@code sesionId}. Mantiene en orden los eventos de una
 * misma sesion y, al ser de alta cardinalidad, reparte la carga de forma pareja
 * entre los tres shards.
 *
 * <p>Los campos opcionales dependen del tipo; esa regla cruzada la valida el
 * dominio de tendencias, no estas anotaciones.
 */
public record EventoActividad(
        @NotNull @Positive Integer version,
        @NotBlank String idEvento,
        @NotNull TipoActividad tipo,
        @NotBlank @Size(max = 64) String sesionId,
        Long clienteId,
        Long productoId,
        @Size(max = 120) String productoNombre,
        @Size(max = 60) String categoria,
        @Size(max = 80) String termino,
        @NotNull Instant ocurridoEn) {
}
