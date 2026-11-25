package com.pedidos360.backend.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.pedidos360.backend.streaming.EventoActividad.TipoActividad;
import com.pedidos360.backend.streaming.PublicadorStreamActividad.ResultadoLote;

import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResultEntry;
import tools.jackson.databind.json.JsonMapper;

/**
 * PutRecords puede aceptar la llamada y rechazar registros sueltos: el
 * productor debe revisar cada resultado y reenviar solo los rechazados.
 */
class KinesisPublicadorActividadTest {

    @Test
    void reenviaSoloLosRegistrosRechazadosDelLote() {
        KinesisFalso kinesis = new KinesisFalso(1);
        KinesisPublicadorActividad publicador = new KinesisPublicadorActividad(kinesis, JsonMapper.builder().build(),
                "pedido360-actividad", 3, 1);

        ResultadoLote resultado = publicador.publicarLote(eventos(4));

        assertThat(resultado.publicados()).isEqualTo(4);
        assertThat(resultado.rechazados()).isZero();
        assertThat(resultado.intentos()).isEqualTo(2);
        assertThat(resultado.erroresPorCodigo()).containsEntry("ProvisionedThroughputExceededException", 1);
        assertThat(kinesis.tamanosDeLlamada).containsExactly(4, 1);
        assertThat(kinesis.clavesDeParticion).allMatch("sesion-abc"::equals);
    }

    @Test
    void informaLosQueSiguenRechazadosDespuesDelUltimoIntento() {
        KinesisFalso kinesis = new KinesisFalso(Integer.MAX_VALUE);
        KinesisPublicadorActividad publicador = new KinesisPublicadorActividad(kinesis, JsonMapper.builder().build(),
                "pedido360-actividad", 3, 1);

        ResultadoLote resultado = publicador.publicarLote(eventos(2));

        assertThat(resultado.publicados()).isEqualTo(1);
        assertThat(resultado.rechazados()).isEqualTo(1);
        assertThat(resultado.intentos()).isEqualTo(3);
    }

    private static List<EventoActividad> eventos(int cantidad) {
        return IntStream.range(0, cantidad)
                .mapToObj(i -> new EventoActividad(1, UUID.randomUUID().toString(), TipoActividad.PRODUCTO_AGREGADO,
                        "sesion-abc", 7L, (long) i + 1, "Producto " + i, null, null, Instant.now()))
                .toList();
    }

    /** Rechaza el ultimo registro de cada llamada mientras le queden rechazos. */
    private static final class KinesisFalso implements KinesisClient {
        private int rechazosRestantes;
        private final List<Integer> tamanosDeLlamada = new ArrayList<>();
        private final List<String> clavesDeParticion = new ArrayList<>();

        private KinesisFalso(int rechazos) {
            this.rechazosRestantes = rechazos;
        }

        @Override
        public PutRecordsResponse putRecords(PutRecordsRequest peticion) {
            int n = peticion.records().size();
            tamanosDeLlamada.add(n);
            peticion.records().forEach(r -> clavesDeParticion.add(r.partitionKey()));

            List<PutRecordsResultEntry> resultados = new ArrayList<>();
            int fallidos = 0;
            for (int i = 0; i < n; i++) {
                boolean rechazar = i == n - 1 && rechazosRestantes > 0;
                if (rechazar) {
                    rechazosRestantes--;
                    fallidos++;
                    resultados.add(PutRecordsResultEntry.builder()
                            .errorCode("ProvisionedThroughputExceededException")
                            .errorMessage("Rate exceeded for shard shardId-000000000002")
                            .build());
                } else {
                    resultados.add(PutRecordsResultEntry.builder()
                            .shardId("shardId-000000000002").sequenceNumber("4965" + i).build());
                }
            }
            return PutRecordsResponse.builder().failedRecordCount(fallidos).records(resultados).build();
        }

        @Override
        public String serviceName() {
            return "kinesis";
        }

        @Override
        public void close() {
        }
    }
}
