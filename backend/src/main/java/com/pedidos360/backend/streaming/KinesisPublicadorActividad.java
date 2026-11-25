package com.pedidos360.backend.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResultEntry;
import tools.jackson.databind.ObjectMapper;

/**
 * Productor del stream de actividad, por lote ({@code PutRecords}).
 *
 * <p>La actividad llega en rafagas (el navegador junta varios clics y los envia
 * juntos), asi que se publica por lote: una sola llamada para hasta 500
 * registros.
 *
 * <p><b>Verificacion del resultado.</b> {@code PutRecords} no es atomico: la
 * llamada puede responder 200 y aun asi rechazar algunos registros (por
 * ejemplo, por superar el limite de escritura de un shard). La respuesta indica
 * cuantos fallaron ({@code FailedRecordCount}) y, registro por registro, el
 * codigo de error. Aqui se reenvian <em>solo</em> los rechazados, con espera
 * creciente, hasta {@code lote.max-intentos}. Lo que siga rechazado se informa
 * en el resultado y en el log.
 */
public class KinesisPublicadorActividad implements PublicadorStreamActividad {

    private static final Logger log = LoggerFactory.getLogger(KinesisPublicadorActividad.class);

    /** Limite de Kinesis por llamada a PutRecords. */
    static final int MAX_REGISTROS_POR_LLAMADA = 500;

    private final KinesisClient kinesis;
    private final ObjectMapper objectMapper;
    private final String stream;
    private final int maxIntentos;
    private final long esperaBaseMs;

    public KinesisPublicadorActividad(KinesisClient kinesis, ObjectMapper objectMapper, String stream,
            int maxIntentos, long esperaBaseMs) {
        this.kinesis = kinesis;
        this.objectMapper = objectMapper;
        this.stream = stream;
        this.maxIntentos = maxIntentos;
        this.esperaBaseMs = esperaBaseMs;
    }

    @Override
    public ResultadoLote publicarLote(List<EventoActividad> eventos) {
        int publicados = 0;
        int rechazados = 0;
        int intentos = 0;
        Map<String, Integer> errores = new TreeMap<>();

        for (int desde = 0; desde < eventos.size(); desde += MAX_REGISTROS_POR_LLAMADA) {
            List<EventoActividad> tramo = eventos.subList(desde, Math.min(eventos.size(), desde + MAX_REGISTROS_POR_LLAMADA));
            ResultadoLote parcial = publicarTramo(tramo, errores);
            publicados += parcial.publicados();
            rechazados += parcial.rechazados();
            intentos += parcial.intentos();
        }

        ResultadoLote resultado = new ResultadoLote(eventos.size(), publicados, rechazados, intentos, errores);
        if (rechazados > 0) {
            log.warn("Lote de actividad con rechazos en {} | {}", stream, resultado);
        } else {
            log.info("Lote de actividad publicado en {} | {} eventos | {} llamada(s)", stream, publicados, intentos);
        }
        return resultado;
    }

    private ResultadoLote publicarTramo(List<EventoActividad> tramo, Map<String, Integer> errores) {
        List<EventoActividad> pendientes = new ArrayList<>(tramo);
        int publicados = 0;
        int intento = 0;

        while (!pendientes.isEmpty() && intento < maxIntentos) {
            intento++;
            List<EventoActividad> rechazadosEsteIntento = new ArrayList<>();

            try {
                PutRecordsResponse respuesta = kinesis.putRecords(PutRecordsRequest.builder()
                        .streamName(stream)
                        .records(pendientes.stream().map(this::aEntrada).toList())
                        .build());

                // Los resultados vienen en el mismo orden que los registros enviados.
                List<PutRecordsResultEntry> resultados = respuesta.records();
                for (int i = 0; i < resultados.size(); i++) {
                    PutRecordsResultEntry resultado = resultados.get(i);
                    if (resultado.errorCode() == null) {
                        publicados++;
                    } else {
                        rechazadosEsteIntento.add(pendientes.get(i));
                        errores.merge(resultado.errorCode(), 1, Integer::sum);
                    }
                }

                if (!rechazadosEsteIntento.isEmpty()) {
                    log.warn("PutRecords intento {} de {}: {} de {} registros rechazados (FailedRecordCount={})",
                            intento, maxIntentos, rechazadosEsteIntento.size(), pendientes.size(),
                            respuesta.failedRecordCount());
                }

            } catch (Exception e) {
                // La llamada completa fallo (red, permisos, stream inexistente).
                log.warn("PutRecords intento {} de {} fallo completo: {}", intento, maxIntentos, e.getMessage());
                errores.merge(e.getClass().getSimpleName(), pendientes.size(), Integer::sum);
                rechazadosEsteIntento.addAll(pendientes);
            }

            pendientes = rechazadosEsteIntento;
            if (!pendientes.isEmpty() && intento < maxIntentos) {
                dormir(esperaBaseMs * (1L << (intento - 1)));
            }
        }

        return new ResultadoLote(tramo.size(), publicados, pendientes.size(), intento, Map.of());
    }

    private PutRecordsRequestEntry aEntrada(EventoActividad evento) {
        return PutRecordsRequestEntry.builder()
                .partitionKey(evento.claveParticion())
                .data(SdkBytes.fromByteArray(objectMapper.writeValueAsBytes(evento)))
                .build();
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
