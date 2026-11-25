package com.pedidos360.streaming.eventos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Evento del stream {@code pedido360-pedidos}, del lado consumidor.
 *
 * <p>Es una copia del contrato que publica el servicio de pedidos: cada
 * microservicio tiene su propia clase para no compartir codigo entre
 * despliegues. Las anotaciones de validacion son la primera barrera del
 * consumidor: un evento que no las cumple no se procesa, se deriva al stream de
 * fallidos con la lista de campos invalidos.
 *
 * <p>Clave de particion: {@code pedidoId}. Todos los eventos de un mismo pedido
 * caen en el mismo shard y se leen en el orden en que se publicaron.
 */
public record EventoPedidoStream(
        /** Version del contrato. Permite evolucionar el evento sin romper consumidores. */
        @NotNull @Positive Integer version,
        /** Identificador unico: clave de idempotencia y de correlacion en los logs. */
        @NotBlank String idEvento,
        @NotNull TipoEventoPedido tipo,
        @NotNull @Positive Long pedidoId,
        String estadoAnterior,
        @NotBlank String estadoNuevo,
        @NotNull @Positive Long clienteId,
        @NotNull @PositiveOrZero BigDecimal total,
        @NotNull @PositiveOrZero Integer cantidadItems,
        @Valid List<ItemEventoPedido> items,
        @NotNull Instant ocurridoEn,
        /** Quien lo genero: el servicio de pedidos o una prueba del administrador. */
        String origen) {
}
