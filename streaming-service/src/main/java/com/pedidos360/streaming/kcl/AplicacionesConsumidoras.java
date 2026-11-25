package com.pedidos360.streaming.kcl;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.pedidos360.streaming.comun.ManejadorDominio;
import com.pedidos360.streaming.comun.RepositorioEstado;
import com.pedidos360.streaming.comun.SimuladorFallas;
import com.pedidos360.streaming.config.StreamingProperties;
import com.pedidos360.streaming.config.StreamingProperties.Aplicacion;

import jakarta.validation.Validator;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.kinesis.common.ConfigsBuilder;
import software.amazon.kinesis.common.InitialPositionInStream;
import software.amazon.kinesis.common.InitialPositionInStreamExtended;
import software.amazon.kinesis.coordinator.Scheduler;
import software.amazon.kinesis.metrics.MetricsConfig;
import software.amazon.kinesis.metrics.MetricsLevel;
import software.amazon.kinesis.retrieval.polling.PollingConfig;
import tools.jackson.databind.ObjectMapper;

/**
 * Arranca y detiene las aplicaciones consumidoras (consumer groups) declaradas
 * en {@code pedido360.streaming.aplicaciones}.
 *
 * <p>Por cada aplicacion activa se crea un {@link Scheduler} de la KCL, que:
 * <ul>
 * <li>crea (si no existe) la <b>tabla de coordinacion</b> en DynamoDB con el
 * nombre de la aplicacion: una fila (lease) por shard, con su duenio y su
 * checkpoint;
 * <li>reparte los shards entre todas las instancias que usan el mismo nombre de
 * aplicacion, y los reasigna si una deja de renovar sus leases;
 * <li>crea un {@link ProcesadorShard} por cada shard asignado a esta instancia.
 * </ul>
 *
 * <p>Dos aplicaciones distintas sobre el mismo stream tienen tablas distintas y
 * por lo tanto checkpoints independientes: cada una lee todos los eventos a su
 * propio ritmo.
 */
@Component
public class AplicacionesConsumidoras implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AplicacionesConsumidoras.class);

    private static final long ESPERA_APAGADO_SEGUNDOS = 20;

    private final StreamingProperties propiedades;
    private final Map<String, ManejadorDominio<?>> manejadoresPorDominio;
    private final KinesisAsyncClient kinesis;
    private final DynamoDbAsyncClient dynamo;
    private final CloudWatchAsyncClient cloudWatch;
    private final ObjectMapper objectMapper;
    private final Validator validador;
    private final RepositorioEstado repositorio;
    private final PublicadorFallidos publicadorFallidos;
    private final PoliticaReintentos reintentos;
    private final SimuladorFallas simuladorFallas;
    private final EstadisticasConsumo estadisticas;
    private final String instancia;
    private final String workerId;
    private final boolean consumir;

    private final List<Scheduler> schedulers = new ArrayList<>();
    private volatile boolean corriendo;

    public AplicacionesConsumidoras(StreamingProperties propiedades, List<ManejadorDominio<?>> manejadores,
            KinesisAsyncClient kinesis, DynamoDbAsyncClient dynamo, CloudWatchAsyncClient cloudWatch,
            ObjectMapper objectMapper, Validator validador, RepositorioEstado repositorio,
            PublicadorFallidos publicadorFallidos, PoliticaReintentos reintentos, SimuladorFallas simuladorFallas,
            EstadisticasConsumo estadisticas, @Value("${pedido360.instancia:unica}") String instancia,
            @Value("${pedido360.streaming.consumir:true}") boolean consumir) {
        this.propiedades = propiedades;
        this.manejadoresPorDominio = manejadores.stream()
                .collect(Collectors.toMap(ManejadorDominio::dominio, Function.identity()));
        this.kinesis = kinesis;
        this.dynamo = dynamo;
        this.cloudWatch = cloudWatch;
        this.objectMapper = objectMapper;
        this.validador = validador;
        this.repositorio = repositorio;
        this.publicadorFallidos = publicadorFallidos;
        this.reintentos = reintentos;
        this.simuladorFallas = simuladorFallas;
        this.estadisticas = estadisticas;
        this.instancia = instancia;
        this.workerId = construirWorkerId(instancia);
        this.consumir = consumir;
    }

    /**
     * Con {@code pedido360.streaming.consumir=false} el servicio levanta solo su
     * API (tableros y control) sin leer los streams. Sirve para las pruebas y
     * para consultar los tableros sin sumar un worker al reparto.
     */
    @Override
    public boolean isAutoStartup() {
        return consumir;
    }

    /**
     * Identificador del worker en la tabla de coordinacion (columna
     * {@code leaseOwner}). Lleva el nombre de la instancia para reconocerla a
     * simple vista, y un sufijo unico por si dos procesos usan el mismo nombre.
     */
    private static String construirWorkerId(String instancia) {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "host";
        }
        return instancia + "@" + host + "#" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String workerId() {
        return workerId;
    }

    public String instancia() {
        return instancia;
    }

    @Override
    public synchronized void start() {
        for (Aplicacion aplicacion : propiedades.getAplicaciones()) {
            if (!aplicacion.isActiva()) {
                log.info("Aplicacion {} desactivada en esta instancia", aplicacion.getNombre());
                continue;
            }
            Scheduler scheduler = crearScheduler(aplicacion);
            schedulers.add(scheduler);

            Thread hilo = new Thread(scheduler, "kcl-" + aplicacion.getNombre());
            hilo.setDaemon(true);
            hilo.start();

            log.info("Aplicacion consumidora {} iniciada | stream={} | posicionInicial={} | worker={}",
                    aplicacion.getNombre(), propiedades.nombreStream(aplicacion.getStream()),
                    aplicacion.getPosicionInicial(), workerId);
        }
        corriendo = true;
    }

    private Scheduler crearScheduler(Aplicacion aplicacion) {
        String stream = propiedades.nombreStream(aplicacion.getStream());
        ManejadorDominio<?> manejador = manejadoresPorDominio.get(aplicacion.getDominio());
        if (manejador == null) {
            throw new IllegalStateException("No hay dominio '" + aplicacion.getDominio()
                    + "' para la aplicacion " + aplicacion.getNombre());
        }

        ProcesadorRegistros<?> procesador = crearProcesador(aplicacion.getNombre(), manejador);

        ConfigsBuilder configs = new ConfigsBuilder(stream, aplicacion.getNombre(), kinesis, dynamo, cloudWatch,
                workerId, () -> new ProcesadorShard(procesador, estadisticas, stream));

        StreamingProperties.Lectura lectura = propiedades.getLectura();

        // Lectura por polling (GetRecords), con lote y pausa configurados.
        PollingConfig polling = new PollingConfig(stream, kinesis)
                .maxRecords(lectura.getMaxRegistros())
                .idleTimeBetweenReadsInMillis(lectura.getPausaEntreLecturasMs());

        InitialPositionInStreamExtended posicion = InitialPositionInStreamExtended.newInitialPosition(
                InitialPositionInStream.valueOf(aplicacion.getPosicionInicial()));

        // Metricas por shard en CloudWatch (namespace = nombre de la
        // aplicacion): MillisBehindLatest, RecordsProcessed, etc.
        MetricsConfig metricas = configs.metricsConfig()
                .metricsLevel(MetricsLevel.SUMMARY)
                .metricsEnabledDimensions(MetricsConfig.METRICS_ALWAYS_ENABLED_DIMENSIONS_WITH_SHARD_ID);

        return new Scheduler(
                configs.checkpointConfig(),
                configs.coordinatorConfig(),
                configs.leaseManagementConfig()
                        .failoverTimeMillis(lectura.getFailoverMs())
                        .shardSyncIntervalMillis(lectura.getSincronizacionShardsMs()),
                configs.lifecycleConfig(),
                metricas,
                configs.processorConfig(),
                configs.retrievalConfig()
                        .initialPositionInStreamExtended(posicion)
                        .retrievalSpecificConfig(polling));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private ProcesadorRegistros<?> crearProcesador(String nombreAplicacion, ManejadorDominio<?> manejador) {
        return new ProcesadorRegistros(nombreAplicacion, manejador, objectMapper, validador, repositorio,
                publicadorFallidos, reintentos, simuladorFallas, instancia);
    }

    /**
     * Apagado ordenado: cada procesador hace su checkpoint final y libera sus
     * leases, para que otra instancia los tome de inmediato y no tenga que
     * esperar el failover.
     */
    @Override
    public synchronized void stop() {
        for (Scheduler scheduler : schedulers) {
            try {
                scheduler.startGracefulShutdown().get(ESPERA_APAGADO_SEGUNDOS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Apagado ordenado incompleto, se fuerza: {}", e.getMessage());
                scheduler.shutdown();
            }
        }
        schedulers.clear();
        corriendo = false;
        log.info("Aplicaciones consumidoras detenidas | worker={}", workerId);
    }

    @Override
    public boolean isRunning() {
        return corriendo;
    }
}
