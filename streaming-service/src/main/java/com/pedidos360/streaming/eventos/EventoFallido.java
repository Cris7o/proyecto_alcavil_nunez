package com.pedidos360.streaming.eventos;

import java.time.Instant;

/**
 * Lo que se publica en el stream de fallidos ({@code pedido360-fallidos}).
 *
 * <p>Lleva el contenido original intacto y todo lo necesario para diagnosticar
 * y reprocesar: de que stream, shard y posicion venia, que aplicacion fallo,
 * en que intento y por que.
 */
public record EventoFallido(
        int version,
        /** idEvento original, si se pudo leer. Null cuando el registro era ilegible. */
        String idEvento,
        String aplicacion,
        String streamOrigen,
        String shardId,
        String sequenceNumber,
        String partitionKey,
        TipoFalla tipoFalla,
        String causa,
        int intentos,
        /** Contenido original del registro, como texto UTF-8. */
        String contenidoOriginal,
        String instancia,
        Instant fallidoEn) {

    /** Por que un evento termino en el stream de fallidos. */
    public enum TipoFalla {
        /** El registro no es JSON o no corresponde al contrato. */
        ILEGIBLE,
        /** El JSON se leyo, pero no cumple las validaciones del DTO. */
        INVALIDO,
        /** La regla de negocio lo rechaza: reintentar no cambiaria nada. */
        NO_RECUPERABLE,
        /** Error transitorio que persistio despues de todos los intentos. */
        REINTENTOS_AGOTADOS
    }
}
