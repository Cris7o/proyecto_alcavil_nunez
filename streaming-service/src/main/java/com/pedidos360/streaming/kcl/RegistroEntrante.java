package com.pedidos360.streaming.kcl;

import java.time.Instant;

/**
 * Un registro leido del stream, con su posicion. Independiza la logica de
 * procesamiento de las clases de la KCL, para poder probarla sin Kinesis.
 */
public record RegistroEntrante(
        String stream,
        String shardId,
        String sequenceNumber,
        String partitionKey,
        Instant llegada,
        byte[] datos) {
}
