package com.pedidos360.admin.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pedidos360.admin.config.StreamingProperties;
import com.pedidos360.admin.dto.StreamDto;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

/**
 * Supervision de los streams con CloudWatch: metricas del stream y por shard, y
 * la alarma de retraso de consumo.
 *
 * <p>Que problema anticipa cada metrica:
 * <ul>
 * <li>{@code IncomingRecords}: volumen que entra. Comparado entre shards
 * muestra si la clave de particion reparte bien (un shard con mucho mas es un
 * shard caliente).
 * <li>{@code OutgoingRecords}: lo que leen los consumidores, por shard.
 * <li>{@code GetRecords.IteratorAgeMilliseconds}: edad del registro mas viejo
 * entregado. Si crece, los consumidores se atrasan; si alcanzara la retencion,
 * se perderian eventos sin procesar.
 * <li>{@code WriteProvisionedThroughputExceeded}: escrituras rechazadas por
 * superar la capacidad de un shard. Indica que faltan shards o hay un shard
 * caliente.
 * <li>{@code ReadProvisionedThroughputExceeded}: lecturas rechazadas (limite de
 * 5 por segundo por shard compartido entre aplicaciones).
 * </ul>
 */
@Service
public class StreamMonitoreoService {

    private static final Logger log = LoggerFactory.getLogger(StreamMonitoreoService.class);

    private static final String NAMESPACE_KINESIS = "AWS/Kinesis";
    private static final int PERIODO_SEGUNDOS = 60;

    /** Metricas del stream completo y el estadistico con que se leen. */
    private static final Map<String, Statistic> METRICAS_STREAM = new LinkedHashMap<>();
    static {
        METRICAS_STREAM.put("IncomingRecords", Statistic.SUM);
        METRICAS_STREAM.put("GetRecords.IteratorAgeMilliseconds", Statistic.MAXIMUM);
        METRICAS_STREAM.put("WriteProvisionedThroughputExceeded", Statistic.SUM);
        METRICAS_STREAM.put("ReadProvisionedThroughputExceeded", Statistic.SUM);
    }

    /** Metricas por shard (requieren monitoreo mejorado activo en el stream). */
    private static final List<String> METRICAS_SHARD = List.of("IncomingRecords", "OutgoingRecords");

    private final CloudWatchClient cloudWatch;
    private final StreamAdminService streamAdmin;
    private final StreamingProperties propiedades;

    public StreamMonitoreoService(CloudWatchClient cloudWatch, StreamAdminService streamAdmin,
            StreamingProperties propiedades) {
        this.cloudWatch = cloudWatch;
        this.streamAdmin = streamAdmin;
        this.propiedades = propiedades;
    }

    public StreamDto.Metricas metricas(String stream, int minutos) {
        Instant fin = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        Instant inicio = fin.minus(minutos, ChronoUnit.MINUTES);

        List<MetricDataQuery> consultas = new ArrayList<>();
        Map<String, String[]> significado = new LinkedHashMap<>();

        int i = 0;
        for (Map.Entry<String, Statistic> metrica : METRICAS_STREAM.entrySet()) {
            String id = "s" + i++;
            consultas.add(consulta(id, metrica.getKey(), metrica.getValue(), dimension("StreamName", stream)));
            significado.put(id, new String[] { metrica.getKey(), null });
        }

        List<String> shards = streamAdmin.metricasPorShardActivas(stream).isEmpty()
                ? List.of()
                : streamAdmin.shardsAbiertos(stream);
        for (String shardId : shards) {
            for (String metrica : METRICAS_SHARD) {
                String id = "h" + i++;
                consultas.add(consulta(id, metrica, Statistic.SUM, dimension("StreamName", stream),
                        dimension("ShardId", shardId)));
                significado.put(id, new String[] { metrica, shardId });
            }
        }

        Map<String, List<StreamDto.Punto>> delStream = new LinkedHashMap<>();
        Map<String, Map<String, List<StreamDto.Punto>>> porShard = new LinkedHashMap<>();

        String token = null;
        do {
            GetMetricDataResponse respuesta = cloudWatch.getMetricData(GetMetricDataRequest.builder()
                    .startTime(inicio)
                    .endTime(fin)
                    .metricDataQueries(consultas)
                    .nextToken(token)
                    .build());

            for (MetricDataResult resultado : respuesta.metricDataResults()) {
                String[] que = significado.get(resultado.id());
                List<StreamDto.Punto> puntos = puntos(resultado);
                if (que[1] == null) {
                    delStream.computeIfAbsent(que[0], k -> new ArrayList<>()).addAll(puntos);
                } else {
                    porShard.computeIfAbsent(que[0], k -> new LinkedHashMap<>())
                            .computeIfAbsent(que[1], k -> new ArrayList<>()).addAll(puntos);
                }
            }
            token = respuesta.nextToken();
        } while (token != null);

        return new StreamDto.Metricas(stream, minutos, PERIODO_SEGUNDOS, delStream, porShard);
    }

    // =====================================================================
    // Alarma de retraso de consumo
    // =====================================================================

    /**
     * Crea o actualiza la alarma declarada. {@code PutMetricAlarm} es
     * idempotente: con el mismo nombre, actualiza.
     */
    public StreamDto.Alarma declararAlarma() {
        StreamingProperties.Alarma alarma = propiedades.getAlarma();
        String stream = propiedades.stream(alarma.getStream()).getNombre();

        cloudWatch.putMetricAlarm(builder -> builder
                .alarmName(alarma.getNombre())
                .alarmDescription(queAnticipa(stream, alarma))
                .namespace(NAMESPACE_KINESIS)
                .metricName(alarma.getMetrica())
                .dimensions(dimension("StreamName", stream))
                .statistic(Statistic.MAXIMUM)
                .period(alarma.getPeriodoSegundos())
                .evaluationPeriods(alarma.getPeriodosEvaluacion())
                .datapointsToAlarm(alarma.getDatapointsParaAlarma())
                .threshold((double) alarma.getUmbralMs())
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData(alarma.getSinDatos()));

        log.info("Alarma {} declarada sobre {} de {} (umbral {} ms)", alarma.getNombre(), alarma.getMetrica(),
                stream, alarma.getUmbralMs());
        return describirAlarma();
    }

    public StreamDto.Alarma describirAlarma() {
        StreamingProperties.Alarma alarma = propiedades.getAlarma();
        String stream = propiedades.stream(alarma.getStream()).getNombre();

        MetricAlarm existente = cloudWatch.describeAlarms(b -> b.alarmNames(alarma.getNombre()))
                .metricAlarms().stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("La alarma " + alarma.getNombre()
                        + " no existe. Crearla con POST /admin/streams/alarma o declarando la topologia."));

        return new StreamDto.Alarma(
                existente.alarmName(),
                existente.stateValueAsString(),
                existente.stateReason(),
                existente.stateUpdatedTimestamp(),
                existente.metricName(),
                stream,
                existente.threshold(),
                existente.period(),
                existente.evaluationPeriods(),
                existente.datapointsToAlarm() == null ? 0 : existente.datapointsToAlarm(),
                existente.treatMissingData(),
                queAnticipa(stream, alarma));
    }

    public void eliminarAlarma() {
        try {
            cloudWatch.deleteAlarms(b -> b.alarmNames(propiedades.getAlarma().getNombre()));
        } catch (software.amazon.awssdk.services.cloudwatch.model.ResourceNotFoundException e) {
            log.info("La alarma {} no existia", propiedades.getAlarma().getNombre());
        }
    }

    private static String queAnticipa(String stream, StreamingProperties.Alarma alarma) {
        return "Retraso de consumo en " + stream + ": el registro mas viejo entregado supera "
                + alarma.getUmbralMs() / 1000 + " s en " + alarma.getDatapointsParaAlarma() + " de "
                + alarma.getPeriodosEvaluacion() + " minutos. Anticipa un consumidor detenido o que no da "
                + "abasto, antes de que los eventos venzan por retencion. Sin datos tambien alarma: significa "
                + "que nadie esta leyendo el stream.";
    }

    private static MetricDataQuery consulta(String id, String metrica, Statistic estadistico,
            Dimension... dimensiones) {
        return MetricDataQuery.builder()
                .id(id)
                .returnData(true)
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder()
                                .namespace(NAMESPACE_KINESIS)
                                .metricName(metrica)
                                .dimensions(dimensiones)
                                .build())
                        .period(PERIODO_SEGUNDOS)
                        .stat(estadistico.toString())
                        .build())
                .build();
    }

    private static Dimension dimension(String nombre, String valor) {
        return Dimension.builder().name(nombre).value(valor).build();
    }

    private static List<StreamDto.Punto> puntos(MetricDataResult resultado) {
        List<StreamDto.Punto> puntos = new ArrayList<>();
        for (int i = 0; i < resultado.timestamps().size(); i++) {
            puntos.add(new StreamDto.Punto(resultado.timestamps().get(i), resultado.values().get(i)));
        }
        puntos.sort((a, b) -> a.instante().compareTo(b.instante()));
        return puntos;
    }
}
