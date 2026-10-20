package com.pedidos360.admin.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pedidos360.admin.config.MensajeriaProperties;
import com.pedidos360.admin.config.MensajeriaProperties.Cola;
import com.pedidos360.admin.dto.MonitoreoDto;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

/**
 * Alarmas y metricas de las colas.
 *
 * <p>Declara en codigo una alarma de CloudWatch por cada DLQ: en cuanto aparece
 * un mensaje sin entregar, la alarma pasa a estado ALARM. Es la unica forma de
 * enterarse de que algo fallo sin estar mirando la consola, porque un mensaje en
 * la DLQ no genera ningun error visible en ninguna parte.
 *
 * <p>Tener las alarmas declaradas aqui, y no creadas a mano en la consola,
 * significa que se recrean solas al aplicar la topologia.
 */
@Service
public class MonitoreoService {

    private static final Logger log = LoggerFactory.getLogger(MonitoreoService.class);

    private static final String NAMESPACE_SQS = "AWS/SQS";
    private static final String DIMENSION_COLA = "QueueName";

    private static final String METRICA_DISPONIBLES = "ApproximateNumberOfMessagesVisible";
    private static final String METRICA_EN_PROCESO = "ApproximateNumberOfMessagesNotVisible";
    private static final String METRICA_ANTIGUEDAD = "ApproximateAgeOfOldestMessage";

    /** Un solo mensaje en la DLQ ya amerita revisar: el umbral es 1. */
    private static final double UMBRAL_MENSAJES_EN_DLQ = 1;

    /** Granularidad de las metricas de SQS en CloudWatch. */
    private static final int PERIODO_SEGUNDOS = 300;

    private final CloudWatchClient cloudWatchClient;
    private final MensajeriaProperties propiedades;

    public MonitoreoService(CloudWatchClient cloudWatchClient, MensajeriaProperties propiedades) {
        this.cloudWatchClient = cloudWatchClient;
        this.propiedades = propiedades;
    }

    /**
     * Crea o actualiza una alarma por DLQ. {@code PutMetricAlarm} es
     * idempotente: volver a llamarlo con el mismo nombre actualiza la alarma.
     */
    public MonitoreoDto.ResultadoDeclaracionAlarmas declararAlarmas() {
        List<String> creadas = new ArrayList<>();

        for (Cola cola : propiedades.getColas()) {
            String nombreDlq = cola.getDlq().getNombre();
            String nombreAlarma = nombreDlq + "-con-mensajes";

            try {
                cloudWatchClient.putMetricAlarm(PutMetricAlarmRequest.builder()
                        .alarmName(nombreAlarma)
                        .alarmDescription(
                                "Hay mensajes sin entregar en " + nombreDlq
                                        + ". Inspeccionar con GET /admin/dlq/" + cola.getId()
                                        + " y reprocesar o purgar segun la causa.")
                        .namespace(NAMESPACE_SQS)
                        .metricName(METRICA_DISPONIBLES)
                        .dimensions(Dimension.builder()
                                .name(DIMENSION_COLA)
                                .value(nombreDlq)
                                .build())
                        // Maximo y no promedio: un promedio de 0,2 mensajes
                        // esconderia que hubo uno.
                        .statistic(Statistic.MAXIMUM)
                        .period(PERIODO_SEGUNDOS)
                        .evaluationPeriods(1)
                        .threshold(UMBRAL_MENSAJES_EN_DLQ)
                        .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
                        // Sin datos significa que no hubo mensajes, no que algo
                        // ande mal: no debe disparar la alarma.
                        .treatMissingData("notBreaching")
                        .build());

                creadas.add(nombreAlarma);
                log.info("Alarma '{}' declarada sobre {} >= {} en {}",
                        nombreAlarma, METRICA_DISPONIBLES, UMBRAL_MENSAJES_EN_DLQ, nombreDlq);

            } catch (Exception e) {
                // Si el rol del laboratorio no permite crear alarmas, se avisa
                // y se sigue: el resto de la topologia no depende de esto.
                log.warn("No se pudo declarar la alarma '{}': {}", nombreAlarma, e.getMessage());
            }
        }

        return new MonitoreoDto.ResultadoDeclaracionAlarmas(creadas.size(), creadas);
    }

    /** Estado actual de las alarmas de las DLQ. */
    public List<MonitoreoDto.Alarma> estadoDeAlarmas() {
        List<MonitoreoDto.Alarma> resultado = new ArrayList<>();

        for (Cola cola : propiedades.getColas()) {
            String nombreAlarma = cola.getDlq().getNombre() + "-con-mensajes";

            List<MetricAlarm> alarmas = cloudWatchClient
                    .describeAlarms(peticion -> peticion.alarmNames(nombreAlarma))
                    .metricAlarms();

            for (MetricAlarm alarma : alarmas) {
                resultado.add(new MonitoreoDto.Alarma(
                        alarma.alarmName(),
                        cola.getDlq().getNombre(),
                        alarma.metricName(),
                        alarma.stateValueAsString(),
                        alarma.stateReason(),
                        alarma.threshold() == null ? 0 : alarma.threshold(),
                        alarma.comparisonOperatorAsString(),
                        alarma.period() == null ? 0 : alarma.period(),
                        alarma.alarmDescription()));
            }
        }
        return resultado;
    }

    /** Las cuatro metricas de cada cola declarada, con su interpretacion. */
    public List<MonitoreoDto.MetricasCola> metricas() {
        return propiedades.getColas().stream().map(this::metricasDe).toList();
    }

    private MonitoreoDto.MetricasCola metricasDe(Cola cola) {
        Double disponibles = ultimoValor(cola.getNombre(), METRICA_DISPONIBLES, Statistic.MAXIMUM);
        Double enProceso = ultimoValor(cola.getNombre(), METRICA_EN_PROCESO, Statistic.MAXIMUM);
        Double antiguedad = ultimoValor(cola.getNombre(), METRICA_ANTIGUEDAD, Statistic.MAXIMUM);
        Double enDlq = ultimoValor(cola.getDlq().getNombre(), METRICA_DISPONIBLES, Statistic.MAXIMUM);

        return new MonitoreoDto.MetricasCola(
                cola.getNombre(),
                cola.getDlq().getNombre(),
                disponibles,
                enProceso,
                antiguedad,
                enDlq,
                interpretar(disponibles, enProceso, antiguedad, enDlq));
    }

    /**
     * Que decision tomar segun las metricas. Es el criterio que se explica en la
     * presentacion: una metrica sola no dice nada, lo que importa es la
     * combinacion.
     */
    private String interpretar(Double disponibles, Double enProceso, Double antiguedad, Double enDlq) {
        if (mayorQueCero(enDlq)) {
            return "Hay mensajes en la DLQ: revisar la causa antes de reprocesar.";
        }
        if (antiguedad != null && antiguedad > 300) {
            return "El mensaje mas antiguo lleva mas de 5 minutos esperando: "
                    + "los consumidores no dan abasto o estan pausados.";
        }
        if (mayorQueCero(disponibles) && !mayorQueCero(enProceso)) {
            return "Hay mensajes esperando y ninguno en proceso: "
                    + "probablemente no hay consumidores leyendo.";
        }
        if (mayorQueCero(enProceso)) {
            return "Procesamiento en curso, sin acumulacion.";
        }
        return "Sin pendientes: las colas estan al dia.";
    }

    private boolean mayorQueCero(Double valor) {
        return valor != null && valor > 0;
    }

    /**
     * Ultimo punto de una metrica en la ultima hora. CloudWatch publica las
     * metricas de SQS cada 5 minutos, asi que el valor siempre va un poco
     * atrasado respecto a los atributos de la cola.
     */
    private Double ultimoValor(String nombreCola, String metrica, Statistic estadistica) {
        try {
            Instant ahora = Instant.now();

            List<Datapoint> puntos = cloudWatchClient.getMetricStatistics(
                    GetMetricStatisticsRequest.builder()
                            .namespace(NAMESPACE_SQS)
                            .metricName(metrica)
                            .dimensions(Dimension.builder()
                                    .name(DIMENSION_COLA)
                                    .value(nombreCola)
                                    .build())
                            .startTime(ahora.minus(Duration.ofHours(1)))
                            .endTime(ahora)
                            .period(PERIODO_SEGUNDOS)
                            .statistics(estadistica)
                            .build())
                    .datapoints();

            return puntos.stream()
                    .max(Comparator.comparing(Datapoint::timestamp))
                    .map(Datapoint::maximum)
                    .orElse(null);

        } catch (Exception e) {
            log.warn("No se pudo leer la metrica {} de {}: {}", metrica, nombreCola, e.getMessage());
            return null;
        }
    }
}
