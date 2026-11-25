package com.pedidos360.backend.dto;

import java.time.Instant;
import java.util.List;

import com.pedidos360.backend.streaming.EventoActividad.TipoActividad;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Lote de interacciones que envia el navegador. El tope de eventos por
 * peticion lo aplica el servicio con {@code lote.max-eventos-por-peticion}.
 */
public record ActividadLoteRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9-]+") String sesionId,
        @NotEmpty List<@Valid Interaccion> eventos) {

    /**
     * Una interaccion. El {@code idEvento} lo genera el navegador: si reenvia el
     * mismo lote tras un error de red, los consumidores reconocen los eventos
     * como duplicados.
     */
    public record Interaccion(
            @Pattern(regexp = "[0-9a-fA-F-]{36}") String idEvento,
            @NotNull TipoActividad tipo,
            @Positive Long productoId,
            @Size(max = 120) String productoNombre,
            @Size(max = 60) String categoria,
            @Size(max = 80) String termino,
            @NotNull Instant ocurridoEn) {
    }
}
