package com.pedidos360.mensajeria.comun;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Contrato del mensaje que viaja desde el servicio de pedidos.
 *
 * <p>{@code @JsonIgnoreProperties} tolera campos nuevos: si manana el productor
 * agrega un dato, los consumidores siguen funcionando sin redespliegue.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
