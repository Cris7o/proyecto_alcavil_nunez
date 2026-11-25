package com.pedidos360.admin.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.pedidos360.admin.config.StreamingProperties;
import com.pedidos360.admin.dto.StreamDto;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.StreamDescriptionSummary;
import software.amazon.awssdk.services.kinesis.model.StreamMode;
import software.amazon.awssdk.services.kinesis.model.StreamStatus;

/**
 * Aplica en AWS la plataforma de streaming declarada en {@code application.yml}
 * (infraestructura como codigo, repetible e idempotente).
 *
 * <p>Por cada stream declarado: lo crea si no existe, y si existe ajusta la
 * retencion, la cantidad de shards y las metricas por shard a lo declarado.
 * Tambien crea la tabla de estado (con TTL) y la alarma de retraso. Correrlo
 * dos veces seguidas no cambia nada la segunda vez.
 *
 * <p>{@link #eliminar(boolean)} hace lo inverso: es lo que se corre al terminar
 * de trabajar, porque Kinesis cobra por shard y por hora mientras el stream
 * exista.
 */
@Service
public class StreamTopologiaService {

    private static final Logger log = LoggerFactory.getLogger(StreamTopologiaService.class);

    /** Tablas auxiliares que crea la KCL 3.x junto a la tabla de leases de cada aplicacion. */
    private static final List<String> SUFIJOS_TABLAS_KCL = List.of("", "-CoordinatorState", "-WorkerMetricStats");

    private final StreamAdminService streamAdmin;
    private final StreamMonitoreoService monitoreo;
    private final KinesisClient kinesis;
    private final DynamoDbClient dynamo;
    private final StreamingProperties propiedades;

    public StreamTopologiaService(StreamAdminService streamAdmin, StreamMonitoreoService monitoreo,
            KinesisClient kinesis, DynamoDbClient dynamo, StreamingProperties propiedades) {
        this.streamAdmin = streamAdmin;
        this.monitoreo = monitoreo;
        this.kinesis = kinesis;
        this.dynamo = dynamo;
        this.propiedades = propiedades;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void declararAlIniciar() {
        if (!propiedades.isDeclararAlIniciar()) {
            log.info("Streams no declarados al iniciar (pedido360.streaming.declarar-al-iniciar=false). "
                    + "Usar POST /admin/streams/topologia.");
            return;
        }
        try {
            declarar();
        } catch (Exception e) {
            log.error("No se pudo declarar la plataforma de streaming al iniciar: {}", e.getMessage());
        }
    }

    public List<StreamDto.Operacion> declarar() {
        List<StreamDto.Operacion> operaciones = new ArrayList<>();
        for (StreamingProperties.Stream declarado : propiedades.getStreams()) {
            operaciones.addAll(declararStream(declarado));
        }
        operaciones.add(declararTablaEstado());

        monitoreo.declararAlarma();
        operaciones.add(new StreamDto.Operacion(propiedades.stream(propiedades.getAlarma().getStream()).getNombre(),
                "alarma", "OK", propiedades.getAlarma().getNombre() + " sobre " + propiedades.getAlarma().getMetrica()));

        log.info("Plataforma de streaming declarada: {} operaciones", operaciones.size());
        return operaciones;
    }

    private List<StreamDto.Operacion> declararStream(StreamingProperties.Stream declarado) {
        String nombre = declarado.getNombre();
        List<StreamDto.Operacion> operaciones = new ArrayList<>();

        if (!streamAdmin.existe(nombre)) {
            operaciones.add(streamAdmin.crearStream(new StreamDto.CrearStream(nombre, declarado.getShards(),
                    declarado.getRetencionHoras(), declarado.getModoCapacidad())));
        } else {
            StreamDescriptionSummary actual = streamAdmin.describirResumen(nombre);
            if (actual.streamStatus() != StreamStatus.ACTIVE) {
                streamAdmin.esperarActivo(nombre);
            }
            operaciones.add(paso(nombre, "retencion",
                    () -> streamAdmin.cambiarRetencion(nombre, declarado.getRetencionHoras())));
            if (actual.streamModeDetails() != null
                    && actual.streamModeDetails().streamMode() == StreamMode.PROVISIONED
                    && actual.openShardCount() != declarado.getShards()) {
                operaciones.add(paso(nombre, "cambiar-shards", () -> {
                    StreamDto.Operacion operacion = streamAdmin.cambiarShards(nombre, declarado.getShards());
                    streamAdmin.esperarActivo(nombre);
                    return operacion;
                }));
            }
        }

        operaciones.add(paso(nombre, "metricas-por-shard",
                () -> ajustarMetricasPorShard(nombre, declarado.getMetricasPorShard())));

        operaciones.add(paso(nombre, "etiquetas", () -> {
            kinesis.addTagsToStream(b -> b.streamName(nombre).tags(Map.of(
                    "proyecto", "pedido360",
                    "flujo", declarado.getId(),
                    "claveParticion", declarado.getClaveParticion())));
            return new StreamDto.Operacion(nombre, "etiquetas", "OK", "proyecto, flujo, claveParticion");
        }));
        return operaciones;
    }

    /**
     * Ejecuta un paso de la declaracion. Si AWS lo deniega (en el Learner Lab
     * algunas operaciones estan bloqueadas por politica de la organizacion), se
     * informa como DENEGADO y se sigue con el resto en vez de abortar todo.
     */
    private StreamDto.Operacion paso(String nombre, String operacion,
            java.util.function.Supplier<StreamDto.Operacion> accion) {
        try {
            return accion.get();
        } catch (AwsServiceException e) {
            log.warn("Paso '{}' de {} denegado por AWS: {}", operacion, nombre, e.awsErrorDetails().errorCode());
            return new StreamDto.Operacion(nombre, operacion, "DENEGADO", e.awsErrorDetails().errorCode());
        }
    }

    private StreamDto.Operacion ajustarMetricasPorShard(String nombre, List<String> declaradas) {
        Set<String> activas = streamAdmin.metricasPorShardActivas(nombre);
        Set<String> deseadas = new HashSet<>(declaradas);

        Set<String> sobran = new HashSet<>(activas);
        sobran.removeAll(deseadas);
        Set<String> faltan = new HashSet<>(deseadas);
        faltan.removeAll(activas);

        if (!faltan.isEmpty()) {
            kinesis.enableEnhancedMonitoring(b -> b.streamName(nombre).shardLevelMetricsWithStrings(faltan));
            streamAdmin.esperarActivo(nombre);
        }
        if (!sobran.isEmpty()) {
            kinesis.disableEnhancedMonitoring(b -> b.streamName(nombre).shardLevelMetricsWithStrings(sobran));
            streamAdmin.esperarActivo(nombre);
        }
        return new StreamDto.Operacion(nombre, "metricas-por-shard", "OK", "activas=" + deseadas);
    }

    /**
     * Tabla compartida por las instancias de streaming-service: estado derivado
     * (tableros) y marcas de eventos procesados. On-Demand porque el volumen es
     * bajo e irregular; TTL para que los datos viejos se borren solos.
     */
    private StreamDto.Operacion declararTablaEstado() {
        String tabla = propiedades.getTablaEstado().getNombre();
        if (tablaExiste(tabla)) {
            return new StreamDto.Operacion(tabla, "tabla-estado", "SIN_CAMBIOS", "ya existe");
        }

        dynamo.createTable(b -> b
                .tableName(tabla)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
        dynamo.waiter().waitUntilTableExists(b -> b.tableName(tabla));
        dynamo.updateTimeToLive(b -> b.tableName(tabla).timeToLiveSpecification(ttl -> ttl
                .enabled(true)
                .attributeName(propiedades.getTablaEstado().getAtributoTtl())));

        log.info("Tabla de estado {} creada con TTL", tabla);
        return new StreamDto.Operacion(tabla, "tabla-estado", "CREADA", "On-Demand, TTL en "
                + propiedades.getTablaEstado().getAtributoTtl());
    }

    /**
     * Elimina los streams declarados y la alarma. Con {@code incluirTablas}
     * borra tambien la tabla de estado y las tablas de coordinacion de la KCL
     * (los checkpoints): las aplicaciones vuelven a empezar desde su posicion
     * inicial la proxima vez.
     */
    public List<StreamDto.Operacion> eliminar(boolean incluirTablas) {
        List<StreamDto.Operacion> operaciones = new ArrayList<>();

        for (StreamingProperties.Stream declarado : propiedades.getStreams()) {
            try {
                operaciones.add(streamAdmin.eliminarStream(declarado.getNombre()));
            } catch (NoSuchElementException e) {
                operaciones.add(new StreamDto.Operacion(declarado.getNombre(), "eliminar", "NO_EXISTE", ""));
            }
        }

        monitoreo.eliminarAlarma();
        operaciones.add(new StreamDto.Operacion(propiedades.getAlarma().getNombre(), "eliminar-alarma", "OK", ""));

        if (incluirTablas) {
            List<String> tablas = new ArrayList<>();
            tablas.add(propiedades.getTablaEstado().getNombre());
            propiedades.getAplicaciones().forEach(aplicacion ->
                    SUFIJOS_TABLAS_KCL.forEach(sufijo -> tablas.add(aplicacion.getNombre() + sufijo)));
            for (String tabla : tablas) {
                operaciones.add(eliminarTabla(tabla));
            }
        }
        return operaciones;
    }

    private StreamDto.Operacion eliminarTabla(String tabla) {
        try {
            dynamo.deleteTable(b -> b.tableName(tabla));
            log.warn("Tabla {} eliminada", tabla);
            return new StreamDto.Operacion(tabla, "eliminar-tabla", "OK", "");
        } catch (ResourceNotFoundException e) {
            return new StreamDto.Operacion(tabla, "eliminar-tabla", "NO_EXISTE", "");
        }
    }

    private boolean tablaExiste(String tabla) {
        try {
            dynamo.describeTable(b -> b.tableName(tabla));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }
}
