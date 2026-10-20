package com.pedidos360.backend.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cuerpo del mensaje que viaja por SNS hacia las colas.
 *
 * <p>{@code idEvento} es la clave con la que los consumidores detectan
 * duplicados: SQS estandar garantiza entrega al menos una vez, no exactamente
 * una, asi que el mismo evento puede llegar dos veces y el consumidor debe
 * tolerarlo.
 *
 * <p>{@code intento} no viaja aqui: lo aporta SQS en el atributo
 * {@code ApproximateReceiveCount} de cada recepcion.
 */
public record EventoPedido(
        String idEvento,
        TipoEvento tipo,
        Long pedidoId,
        String estadoAnterior,
        String estadoNuevo,
        Long clienteId,
        String clienteNombre,
        String clienteEmail,
        BigDecimal total,
        Instant ocurridoEn) {
}
