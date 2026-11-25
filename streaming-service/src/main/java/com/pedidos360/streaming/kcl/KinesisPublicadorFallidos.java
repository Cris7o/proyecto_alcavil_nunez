package com.pedidos360.streaming.kcl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pedidos360.streaming.eventos.EventoFallido;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Publica en el stream {@code pedido360-fallidos} (dead letter stream).
 *
 * <p>La clave de particion es el nombre de la aplicacion que fallo: los
 * fallidos de cada aplicacion quedan juntos y en orden, lo que facilita
 * revisarlos y reprocesarlos por aplicacion.
 *
 * <p>Los reintentos ante throttling o errores de red los hace el SDK (estan
 * configurados en {@code cliente.max-reintentos-sdk}). Si aun asi no se pudo
 * publicar, el evento queda completo en el log de error: es el ultimo recurso
 * para no perderlo sin dejar rastro, y no se detiene el shard por ello.
 */
public class KinesisPublicadorFallidos implements PublicadorFallidos {

    private static final Logger log = LoggerFactory.getLogger(KinesisPublicadorFallidos.class);

    private final KinesisClient kinesis;
    private final ObjectMapper objectMapper;
    private final String streamFallidos;

    public KinesisPublicadorFallidos(KinesisClient kinesis, ObjectMapper objectMapper, String streamFallidos) {
        this.kinesis = kinesis;
        this.objectMapper = objectMapper;
        this.streamFallidos = streamFallidos;
    }

    @Override
    public boolean publicar(EventoFallido fallido) {
        try {
            PutRecordResponse respuesta = kinesis.putRecord(PutRecordRequest.builder()
                    .streamName(streamFallidos)
                    .partitionKey(fallido.aplicacion())
                    .data(SdkBytes.fromByteArray(objectMapper.writeValueAsBytes(fallido)))
                    .build());

            log.warn("[{}] Derivado a {} | idEvento={} | tipoFalla={} | intentos={} | causa={} | origen={}/{}/{} | seqFallidos={}",
                    fallido.aplicacion(), streamFallidos, fallido.idEvento(), fallido.tipoFalla(),
                    fallido.intentos(), fallido.causa(), fallido.streamOrigen(), fallido.shardId(),
                    fallido.sequenceNumber(), respuesta.sequenceNumber());
            return true;

        } catch (Exception e) {
            log.error("[{}] NO se pudo publicar en {}. Evento completo para recuperarlo a mano: {} | error={}",
                    fallido.aplicacion(), streamFallidos, fallido, e.getMessage(), e);
            return false;
        }
    }
}
