package com.pedidos360.backend.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Productor del stream de pedidos: un {@code PutRecord} por cambio de estado.
 *
 * <p>Se publica de a un registro (no por lote) porque cada cambio de estado
 * ocurre en una peticion HTTP distinta y conviene que llegue al stream en el
 * momento. El resultado se verifica: la respuesta trae el shard y el numero de
 * secuencia asignados, que quedan en el log para poder seguir el evento hasta
 * el consumidor.
 *
 * <p>Los reintentos ante throttling ({@code ProvisionedThroughputExceeded}) o
 * fallas de red los hace el SDK, con espera exponencial y el limite de
 * {@code pedido360.streaming.cliente.max-reintentos-sdk}.
 */
public class KinesisPublicadorPedidos implements PublicadorStreamPedidos {

    private static final Logger log = LoggerFactory.getLogger(KinesisPublicadorPedidos.class);

    private final KinesisClient kinesis;
    private final ObjectMapper objectMapper;
    private final String stream;

    public KinesisPublicadorPedidos(KinesisClient kinesis, ObjectMapper objectMapper, String stream) {
        this.kinesis = kinesis;
        this.objectMapper = objectMapper;
        this.stream = stream;
    }

    @Override
    public void publicar(EventoPedidoStream evento) {
        try {
            PutRecordResponse respuesta = kinesis.putRecord(PutRecordRequest.builder()
                    .streamName(stream)
                    .partitionKey(evento.claveParticion())
                    .data(SdkBytes.fromByteArray(objectMapper.writeValueAsBytes(evento)))
                    .build());

            log.info("Evento publicado en {} | idEvento={} | tipo={} | pedidoId={} | shard={} | seq={}",
                    stream, evento.idEvento(), evento.tipo(), evento.pedidoId(), respuesta.shardId(),
                    respuesta.sequenceNumber());

        } catch (Exception e) {
            log.error("No se pudo publicar en {} | idEvento={} | tipo={} | pedidoId={} | causa={}",
                    stream, evento.idEvento(), evento.tipo(), evento.pedidoId(), e.getMessage(), e);
        }
    }
}
