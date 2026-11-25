package com.pedidos360.streaming.eventos;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** Linea de un pedido, tal como viaja en el evento. */
public record ItemEventoPedido(
        @NotNull @Positive Long productoId,
        @NotBlank String productoNombre,
        @NotNull @Positive Integer cantidad,
        @NotNull @PositiveOrZero BigDecimal precioUnitario) {
}
