package com.pedidos360.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Contratos de la API REST de administracion de streams. */
public final class StreamDto {

    private StreamDto() {
    }

    /** Fila del listado de streams. */
    public record Resumen(
            String nombre,
            String estado,
            String modoCapacidad,
            Integer shardsAbiertos,
            Integer retencionHoras,
            boolean declarado,
            Integer shardsDeclarados,
            Integer retencionDeclaradaHoras,
            String claveParticion,
            String justificacion) {
    }

    /** Detalle de un stream, con sus shards. */
    public record Detalle(
            String nombre,
            String arn,
            String estado,
            String modoCapacidad,
            int retencionHoras,
            Instant creadoEn,
            String encriptacion,
            List<String> metricasPorShard,
            List<Shard> shards,
            List<String> aplicacionesQueLoLeen,
            String claveParticion,
            String justificacion) {
    }

    /**
     * Un shard. Los cerrados (con {@code secuenciaFinal}) son padres de un
     * resharding: ya no reciben escrituras pero conservan sus registros hasta
     * que vence la retencion.
     */
    public record Shard(
            String shardId,
            boolean abierto,
            String rangoHashInicio,
            String rangoHashFin,
            String secuenciaInicial,
            String secuenciaFinal,
            String padre) {
    }

    public record CrearStream(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{1,128}") String nombre,
            @Min(1) @Max(10) int shards,
            @Min(24) @Max(8760) int retencionHoras,
            @Pattern(regexp = "PROVISIONED|ON_DEMAND") String modoCapacidad) {
    }

    /** Resharding uniforme. Kinesis solo permite entre la mitad y el doble por operacion. */
    public record CambioShards(@Min(1) @Max(10) int shards) {
    }

    /** Entre 24 h (minimo) y 8.760 h (365 dias). Mas de 24 h tiene costo adicional. */
    public record CambioRetencion(@Min(24) @Max(8760) int horas) {
    }

    /** Resultado de una operacion de administracion, para mostrar en el panel. */
    public record Operacion(String stream, String operacion, String resultado, String detalle) {
    }

    /** Aplicacion consumidora y su tabla de coordinacion. */
    public record Aplicacion(
            String nombre,
            String stream,
            String tablaCoordinacion,
            boolean tablaExiste,
            int shardsDelStream,
            Map<String, List<String>> shardsPorInstancia,
            List<Lease> leases,
            Long retrasoMaximoMs,
            String justificacion) {
    }

    /**
     * Una fila de la tabla de coordinacion: quien tiene el shard y hasta donde
     * llego.
     *
     * @param checkpoint        numero de secuencia del ultimo registro
     *                          confirmado, o TRIM_HORIZON / LATEST si aun no
     *                          proceso nada, o SHARD_END si el shard se cerro
     * @param leaseCounter      sube cada vez que el duenio renueva el lease; si
     *                          deja de subir, la instancia murio
     * @param retrasoMs         que tan atras va este shard respecto del ultimo
     *                          registro escrito (MillisBehindLatest)
     */
    public record Lease(
            String shardId,
            String duenio,
            String checkpoint,
            Long subSecuencia,
            Long leaseCounter,
            Long cambiosDeDuenioDesdeCheckpoint,
            Long retrasoMs,
            String nota) {
    }

    /** Un registro leido del stream (visor de datos). */
    public record Registro(
            String shardId,
            String sequenceNumber,
            String partitionKey,
            Instant llegada,
            String contenido) {
    }

    /** Variantes de evento de prueba, para la demostracion. */
    public enum VariantePrueba {
        /** Pedido creado correcto. */
        VALIDO,
        /** El mismo evento publicado dos veces: el consumidor lo descarta como duplicado. */
        DUPLICADO,
        /** JSON sin idEvento y con total negativo: falla la validacion del DTO. */
        INVALIDO,
        /** Contenido que no es JSON. */
        ILEGIBLE,
        /** JSON valido cuyo total no cuadra con los items: regla de negocio. */
        INCOHERENTE
    }

    public record PublicarPrueba(@NotNull VariantePrueba variante) {
    }

    public record RegistroPublicado(String idEvento, String partitionKey, String shardId, String sequenceNumber,
            String contenido) {
    }

    public record ResultadoPrueba(String stream, VariantePrueba variante, String queDeberiaPasar,
            List<RegistroPublicado> publicados) {
    }

    /** Series de CloudWatch de un stream. */
    public record Metricas(
            String stream,
            int minutos,
            int periodoSegundos,
            Map<String, List<Punto>> delStream,
            Map<String, Map<String, List<Punto>>> porShard) {
    }

    public record Punto(Instant instante, double valor) {
    }

    public record Alarma(
            String nombre,
            String estado,
            String motivo,
            Instant actualizadaEn,
            String metrica,
            String stream,
            double umbral,
            int periodoSegundos,
            int periodosEvaluacion,
            int datapointsParaAlarma,
            String sinDatos,
            String queAnticipa) {
    }
}
