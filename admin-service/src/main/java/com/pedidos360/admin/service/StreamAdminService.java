package com.pedidos360.admin.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pedidos360.admin.config.StreamingProperties;
import com.pedidos360.admin.dto.StreamDto;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamSummaryRequest;
import software.amazon.awssdk.services.kinesis.model.EnhancedMetrics;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.ListShardsRequest;
import software.amazon.awssdk.services.kinesis.model.ListShardsResponse;
import software.amazon.awssdk.services.kinesis.model.ListStreamsRequest;
import software.amazon.awssdk.services.kinesis.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException;
import software.amazon.awssdk.services.kinesis.model.ScalingType;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.awssdk.services.kinesis.model.StreamDescriptionSummary;
import software.amazon.awssdk.services.kinesis.model.StreamMode;
import software.amazon.awssdk.services.kinesis.model.StreamModeDetails;
import software.amazon.awssdk.services.kinesis.model.StreamSummary;

/**
 * Administracion de la plataforma de streaming, encapsulada en un solo servicio.
 *
 * <p>Los controladores (y por lo tanto el panel del frontend) solo llaman a
 * estos metodos de alto nivel; ninguna otra clase de la aplicacion usa el SDK de
 * Kinesis para administrar. Cubre:
 * <ul>
 * <li>streams: listar, describir, crear, cambiar shards, cambiar retencion,
 * eliminar;
 * <li>aplicaciones consumidoras: que instancia tiene cada shard, su checkpoint
 * y su retraso, leyendo la tabla de coordinacion de la KCL;
 * <li>visor de datos: leer los ultimos registros de un stream;
 * <li>eventos de prueba para la demostracion.
 * </ul>
 */
@Service
public class StreamAdminService {

    private static final Logger log = LoggerFactory.getLogger(StreamAdminService.class);

    /** Lo que tarda un stream en pasar de CREATING o UPDATING a ACTIVE, como maximo razonable. */
    private static final Duration ESPERA_ACTIVO = Duration.ofMinutes(3);

    private static final Set<String> CHECKPOINTS_ESPECIALES = Set.of("TRIM_HORIZON", "LATEST", "AT_TIMESTAMP");
    private static final String SHARD_END = "SHARD_END";

    private final KinesisClient kinesis;
    private final DynamoDbClient dynamo;
    private final StreamingProperties propiedades;

    public StreamAdminService(KinesisClient kinesis, DynamoDbClient dynamo, StreamingProperties propiedades) {
        this.kinesis = kinesis;
        this.dynamo = dynamo;
        this.propiedades = propiedades;
    }

    // =====================================================================
    // Streams
    // =====================================================================

    /** Streams del proyecto (por prefijo), mas los declarados que todavia no existen. */
    public List<StreamDto.Resumen> listarStreams() {
        Map<String, StreamDto.Resumen> porNombre = new TreeMap<>();

        for (StreamSummary resumen : kinesis.listStreamsPaginator(ListStreamsRequest.builder().build())
                .streamSummaries()) {
            if (!resumen.streamName().startsWith(propiedades.getPrefijo())) {
                continue;
            }
            StreamDescriptionSummary detalle = describirResumen(resumen.streamName());
            porNombre.put(resumen.streamName(), resumenDe(resumen.streamName(), detalle));
        }

        for (StreamingProperties.Stream declarado : propiedades.getStreams()) {
            porNombre.computeIfAbsent(declarado.getNombre(), nombre -> resumenDe(nombre, null));
        }
        return new ArrayList<>(porNombre.values());
    }

    public StreamDto.Detalle describirStream(String nombre) {
        StreamDescriptionSummary resumen = describirResumen(nombre);
        List<StreamDto.Shard> shards = listarShards(nombre).stream()
                .map(shard -> new StreamDto.Shard(
                        shard.shardId(),
                        shard.sequenceNumberRange().endingSequenceNumber() == null,
                        shard.hashKeyRange().startingHashKey(),
                        shard.hashKeyRange().endingHashKey(),
                        shard.sequenceNumberRange().startingSequenceNumber(),
                        shard.sequenceNumberRange().endingSequenceNumber(),
                        shard.parentShardId()))
                .toList();

        List<String> metricas = resumen.enhancedMonitoring().stream()
                .flatMap(m -> m.shardLevelMetricsAsStrings().stream())
                .sorted()
                .toList();

        StreamingProperties.Stream declarado = propiedades.streamPorNombre(nombre).orElse(null);
        List<String> aplicaciones = declarado == null ? List.of() : propiedades.getAplicaciones().stream()
                .filter(aplicacion -> aplicacion.getStream().equals(declarado.getId()))
                .map(StreamingProperties.Aplicacion::getNombre)
                .toList();

        return new StreamDto.Detalle(
                resumen.streamName(),
                resumen.streamARN(),
                resumen.streamStatusAsString(),
                resumen.streamModeDetails() == null ? null : resumen.streamModeDetails().streamModeAsString(),
                resumen.retentionPeriodHours(),
                resumen.streamCreationTimestamp(),
                resumen.encryptionTypeAsString(),
                metricas,
                shards,
                aplicaciones,
                declarado == null ? null : declarado.getClaveParticion(),
                declarado == null ? null : declarado.getJustificacion());
    }

    public StreamDto.Operacion crearStream(StreamDto.CrearStream peticion) {
        StreamMode modo = peticion.modoCapacidad() == null
                ? StreamMode.PROVISIONED
                : StreamMode.fromValue(peticion.modoCapacidad());

        kinesis.createStream(builder -> {
            builder.streamName(peticion.nombre())
                    .streamModeDetails(StreamModeDetails.builder().streamMode(modo).build());
            // En On-Demand los shards los decide AWS.
            if (modo == StreamMode.PROVISIONED) {
                builder.shardCount(peticion.shards());
            }
        });
        log.info("Stream {} creado | modo={} | shards={}", peticion.nombre(), modo, peticion.shards());

        esperarActivo(peticion.nombre());
        String retencion;
        try {
            retencion = ajustarRetencion(peticion.nombre(), peticion.retencionHoras());
        } catch (AwsServiceException e) {
            // El stream ya existe: no se deshace la creacion por no poder
            // ajustar la retencion (en el Learner Lab una SCP lo deniega).
            retencion = "retencion no modificada: " + e.awsErrorDetails().errorCode();
            log.warn("Stream {} creado, pero AWS denego cambiar su retencion: {}", peticion.nombre(),
                    e.awsErrorDetails().errorMessage());
        }

        return new StreamDto.Operacion(peticion.nombre(), "crear", "ACTIVE",
                "modo=" + modo + ", shards=" + peticion.shards() + ", " + retencion);
    }

    /**
     * Resharding uniforme: Kinesis divide o une shards para llegar al numero
     * pedido. Los shards viejos quedan cerrados (con sus registros hasta que
     * vence la retencion) y la KCL pasa a leer los nuevos cuando termina los
     * padres, respetando el orden por clave.
     */
    public StreamDto.Operacion cambiarShards(String nombre, int shards) {
        StreamDescriptionSummary actual = describirResumen(nombre);
        int abiertos = actual.openShardCount();
        if (abiertos == shards) {
            return new StreamDto.Operacion(nombre, "cambiar-shards", "SIN_CAMBIOS", "ya tiene " + shards + " shards");
        }
        if (shards > abiertos * 2 || shards * 2 < abiertos) {
            throw new IllegalArgumentException("Kinesis solo permite entre la mitad y el doble de shards por "
                    + "operacion: de " + abiertos + " se puede ir a un valor entre "
                    + (int) Math.ceil(abiertos / 2.0) + " y " + abiertos * 2);
        }

        kinesis.updateShardCount(builder -> builder
                .streamName(nombre)
                .targetShardCount(shards)
                .scalingType(ScalingType.UNIFORM_SCALING));
        log.info("Resharding de {}: {} -> {} shards", nombre, abiertos, shards);

        return new StreamDto.Operacion(nombre, "cambiar-shards", "UPDATING",
                abiertos + " -> " + shards + " shards. El stream queda en UPDATING unos segundos.");
    }

    public StreamDto.Operacion cambiarRetencion(String nombre, int horas) {
        return new StreamDto.Operacion(nombre, "cambiar-retencion", "OK", ajustarRetencion(nombre, horas));
    }

    /** Elimina el stream. Sus registros se pierden y deja de cobrarse. */
    public StreamDto.Operacion eliminarStream(String nombre) {
        describirResumen(nombre);
        kinesis.deleteStream(builder -> builder.streamName(nombre).enforceConsumerDeletion(true));
        log.warn("Stream {} eliminado", nombre);
        return new StreamDto.Operacion(nombre, "eliminar", "DELETING", "Kinesis deja de cobrarlo en segundos");
    }

    /**
     * Cambia la retencion con la operacion que corresponda (Kinesis tiene una
     * para subir y otra para bajar).
     */
    String ajustarRetencion(String nombre, int horas) {
        int actual = describirResumen(nombre).retentionPeriodHours();
        if (horas > actual) {
            kinesis.increaseStreamRetentionPeriod(b -> b.streamName(nombre).retentionPeriodHours(horas));
        } else if (horas < actual) {
            kinesis.decreaseStreamRetentionPeriod(b -> b.streamName(nombre).retentionPeriodHours(horas));
        } else {
            return "retencion sin cambios (" + horas + " h)";
        }
        log.info("Retencion de {}: {} h -> {} h", nombre, actual, horas);
        return "retencion " + actual + " h -> " + horas + " h";
    }

    void esperarActivo(String nombre) {
        kinesis.waiter().waitUntilStreamExists(
                DescribeStreamRequest.builder().streamName(nombre).build(),
                WaiterOverrideConfiguration.builder().waitTimeout(ESPERA_ACTIVO).build());
    }

    boolean existe(String nombre) {
        try {
            describirResumen(nombre);
            return true;
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    StreamDescriptionSummary describirResumen(String nombre) {
        try {
            return kinesis.describeStreamSummary(DescribeStreamSummaryRequest.builder().streamName(nombre).build())
                    .streamDescriptionSummary();
        } catch (ResourceNotFoundException e) {
            throw new NoSuchElementException("El stream " + nombre + " no existe");
        }
    }

    List<Shard> listarShards(String nombre) {
        List<Shard> shards = new ArrayList<>();
        ListShardsResponse respuesta = kinesis.listShards(ListShardsRequest.builder().streamName(nombre).build());
        shards.addAll(respuesta.shards());
        while (respuesta.nextToken() != null) {
            String token = respuesta.nextToken();
            respuesta = kinesis.listShards(ListShardsRequest.builder().nextToken(token).build());
            shards.addAll(respuesta.shards());
        }
        return shards;
    }

    List<String> shardsAbiertos(String nombre) {
        return listarShards(nombre).stream()
                .filter(shard -> shard.sequenceNumberRange().endingSequenceNumber() == null)
                .map(Shard::shardId)
                .toList();
    }

    private StreamDto.Resumen resumenDe(String nombre, StreamDescriptionSummary detalle) {
        StreamingProperties.Stream declarado = propiedades.streamPorNombre(nombre).orElse(null);
        return new StreamDto.Resumen(
                nombre,
                detalle == null ? "NO_EXISTE" : detalle.streamStatusAsString(),
                detalle == null || detalle.streamModeDetails() == null
                        ? null
                        : detalle.streamModeDetails().streamModeAsString(),
                detalle == null ? null : detalle.openShardCount(),
                detalle == null ? null : detalle.retentionPeriodHours(),
                declarado != null,
                declarado == null ? null : declarado.getShards(),
                declarado == null ? null : declarado.getRetencionHoras(),
                declarado == null ? null : declarado.getClaveParticion(),
                declarado == null ? null : declarado.getJustificacion());
    }

    /** Metricas por shard activas en el stream. */
    Set<String> metricasPorShardActivas(String nombre) {
        Set<String> activas = new HashSet<>();
        for (EnhancedMetrics m : describirResumen(nombre).enhancedMonitoring()) {
            activas.addAll(m.shardLevelMetricsAsStrings());
        }
        return activas;
    }

    // =====================================================================
    // Aplicaciones consumidoras
    // =====================================================================

    public List<StreamDto.Aplicacion> listarAplicaciones() {
        return propiedades.getAplicaciones().stream()
                .map(aplicacion -> inspeccionarAplicacion(aplicacion.getNombre(), false))
                .toList();
    }

    /**
     * Lee la tabla de coordinacion de la aplicacion: una fila por shard, con la
     * instancia que la tiene (leaseOwner) y su checkpoint.
     *
     * @param conRetraso si es true, calcula ademas el retraso de cada shard
     *                   leyendo el stream desde el checkpoint (una lectura por
     *                   shard: no conviene hacerlo en cada refresco del panel)
     */
    public StreamDto.Aplicacion inspeccionarAplicacion(String nombre, boolean conRetraso) {
        StreamingProperties.Aplicacion aplicacion = propiedades.aplicacion(nombre);
        String stream = propiedades.stream(aplicacion.getStream()).getNombre();

        List<Map<String, AttributeValue>> filas;
        try {
            filas = escanear(nombre);
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
            return new StreamDto.Aplicacion(nombre, stream, nombre, false, shardsDelStream(stream), Map.of(),
                    List.of(), null, aplicacion.getJustificacion());
        }

        List<StreamDto.Lease> leases = new ArrayList<>();
        Map<String, List<String>> porInstancia = new TreeMap<>();
        Long retrasoMaximo = null;

        for (Map<String, AttributeValue> fila : filas) {
            String shardId = texto(fila, "leaseKey");
            if (shardId == null || !shardId.startsWith("shardId-")) {
                continue;
            }
            String duenio = texto(fila, "leaseOwner");
            String checkpoint = texto(fila, "checkpoint");

            Long retraso = null;
            String nota = null;
            if (SHARD_END.equals(checkpoint)) {
                nota = "Shard cerrado por resharding y procesado completo";
            } else if (duenio == null) {
                nota = "Sin duenio: ninguna instancia lo esta leyendo";
            }
            if (conRetraso && !SHARD_END.equals(checkpoint)) {
                try {
                    retraso = retrasoDesdeCheckpoint(stream, shardId, checkpoint);
                } catch (ProvisionedThroughputExceededException e) {
                    nota = "Lectura limitada por Kinesis (5 lecturas/s por shard); reintentar en unos segundos";
                } catch (RuntimeException e) {
                    nota = "No se pudo calcular el retraso: " + e.getMessage();
                }
            }
            if (retraso != null) {
                retrasoMaximo = retrasoMaximo == null ? retraso : Math.max(retrasoMaximo, retraso);
            }

            leases.add(new StreamDto.Lease(shardId, duenio, checkpoint, numero(fila, "checkpointSubSequenceNumber"),
                    numero(fila, "leaseCounter"), numero(fila, "ownerSwitchesSinceCheckpoint"), retraso, nota));
            if (duenio != null && !SHARD_END.equals(checkpoint)) {
                porInstancia.computeIfAbsent(duenio, clave -> new ArrayList<>()).add(shardId);
            }
        }

        leases.sort(Comparator.comparing(StreamDto.Lease::shardId));
        return new StreamDto.Aplicacion(nombre, stream, nombre, true, shardsDelStream(stream), porInstancia, leases,
                retrasoMaximo, aplicacion.getJustificacion());
    }

    /**
     * Retraso de un shard respecto de su checkpoint: se pide un iterador justo
     * despues del ultimo registro confirmado y se lee uno. Kinesis responde con
     * {@code MillisBehindLatest}: cuanto tiempo de eventos queda sin procesar.
     * Cero significa que la aplicacion esta al dia.
     */
    Long retrasoDesdeCheckpoint(String stream, String shardId, String checkpoint) {
        GetShardIteratorRequest.Builder iterador = GetShardIteratorRequest.builder()
                .streamName(stream)
                .shardId(shardId);

        if (checkpoint == null || CHECKPOINTS_ESPECIALES.contains(checkpoint)) {
            iterador.shardIteratorType(ShardIteratorType.TRIM_HORIZON);
        } else {
            iterador.shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER).startingSequenceNumber(checkpoint);
        }

        String shardIterator = kinesis.getShardIterator(iterador.build()).shardIterator();
        GetRecordsResponse respuesta = kinesis.getRecords(
                GetRecordsRequest.builder().shardIterator(shardIterator).limit(1).build());
        return respuesta.millisBehindLatest();
    }

    private int shardsDelStream(String stream) {
        try {
            return describirResumen(stream).openShardCount();
        } catch (NoSuchElementException e) {
            return 0;
        }
    }

    private List<Map<String, AttributeValue>> escanear(String tabla) {
        List<Map<String, AttributeValue>> filas = new ArrayList<>();
        Map<String, AttributeValue> desde = null;
        do {
            ScanResponse respuesta = dynamo.scan(ScanRequest.builder().tableName(tabla).exclusiveStartKey(desde).build());
            filas.addAll(respuesta.items());
            desde = respuesta.hasLastEvaluatedKey() && !respuesta.lastEvaluatedKey().isEmpty()
                    ? respuesta.lastEvaluatedKey()
                    : null;
        } while (desde != null);
        return filas;
    }

    private static String texto(Map<String, AttributeValue> fila, String atributo) {
        AttributeValue valor = fila.get(atributo);
        return valor == null ? null : valor.s();
    }

    private static Long numero(Map<String, AttributeValue> fila, String atributo) {
        AttributeValue valor = fila.get(atributo);
        return valor == null || valor.n() == null ? null : Long.parseLong(valor.n());
    }

    // =====================================================================
    // Visor de datos
    // =====================================================================

    /**
     * Ultimos registros de cada shard abierto, desde los mas recientes que
     * sigan retenidos. Sirve para ver que contiene un stream (por ejemplo, la
     * causa de cada evento en el stream de fallidos) sin ir a la consola.
     */
    public List<StreamDto.Registro> leerUltimos(String nombre, int limite) {
        List<StreamDto.Registro> registros = new ArrayList<>();

        for (String shardId : shardsAbiertos(nombre)) {
            String iterador = kinesis.getShardIterator(GetShardIteratorRequest.builder()
                    .streamName(nombre)
                    .shardId(shardId)
                    .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                    .build()).shardIterator();

            // Se avanza hasta la punta del shard con un tope de llamadas para no
            // consumir el limite de lectura que comparten los consumidores.
            for (int llamada = 0; llamada < 10 && iterador != null; llamada++) {
                GetRecordsResponse respuesta = kinesis.getRecords(
                        GetRecordsRequest.builder().shardIterator(iterador).limit(500).build());
                for (Record registro : respuesta.records()) {
                    registros.add(new StreamDto.Registro(shardId, registro.sequenceNumber(),
                            registro.partitionKey(), registro.approximateArrivalTimestamp(),
                            registro.data().asUtf8String()));
                }
                if (respuesta.millisBehindLatest() != null && respuesta.millisBehindLatest() == 0) {
                    break;
                }
                iterador = respuesta.nextShardIterator();
            }
        }

        registros.sort(Comparator.comparing(StreamDto.Registro::llegada).reversed());
        return registros.subList(0, Math.min(limite, registros.size()));
    }

    // =====================================================================
    // Publicacion de prueba
    // =====================================================================

    /** Publica un registro tal cual, para las pruebas de la demostracion. */
    StreamDto.RegistroPublicado publicarCrudo(String stream, String idEvento, String partitionKey,
            String contenido) {
        PutRecordResponse respuesta = kinesis.putRecord(PutRecordRequest.builder()
                .streamName(stream)
                .partitionKey(partitionKey)
                .data(SdkBytes.fromUtf8String(contenido))
                .build());
        log.info("Evento de prueba publicado en {} | idEvento={} | clave={} | shard={} | seq={}",
                stream, idEvento, partitionKey, respuesta.shardId(), respuesta.sequenceNumber());
        return new StreamDto.RegistroPublicado(idEvento, partitionKey, respuesta.shardId(),
                respuesta.sequenceNumber(), contenido);
    }

    /** Copia defensiva para mapas que se serializan como JSON. */
    static Map<String, Object> mapa(Object... claveValor) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i < claveValor.length; i += 2) {
            mapa.put((String) claveValor[i], claveValor[i + 1]);
        }
        return mapa;
    }
}
