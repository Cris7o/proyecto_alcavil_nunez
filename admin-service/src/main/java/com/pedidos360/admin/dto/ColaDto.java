package com.pedidos360.admin.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Contratos de la API de colas.
 *
 * <p>Se agrupan en una sola clase contenedora porque son tipos de datos sin
 * comportamiento: tenerlos juntos deja ver el contrato completo de un vistazo.
 */
public final class ColaDto {

    private ColaDto() {
    }

    /**
     * Peticion para crear una cola.
     *
     * <p>Si viene {@code dlqNombre}, se crea tambien la DLQ y se asocia con una
     * Redrive Policy. Los limites de los campos son los que impone SQS.
     */
    public record CrearCola(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9_-]{1,80}",
                    message = "El nombre solo admite letras, numeros, guion y guion bajo")
            String nombre,

            @Min(60) @Max(1_209_600)
            Integer retencionSegundos,

            @Min(0) @Max(43_200)
            Integer visibilidadSegundos,

            @Min(0) @Max(20)
            Integer esperaLargaSegundos,

            @Pattern(regexp = "[A-Za-z0-9_-]{1,80}",
                    message = "El nombre de la DLQ solo admite letras, numeros, guion y guion bajo")
            String dlqNombre,

            @Min(1) @Max(1_000)
            Integer maxReceiveCount) {
    }

    /** Una cola, tal como aparece al listar. */
    public record Resumen(String nombre, String url) {
    }

    /**
     * Estado operativo de una cola.
     *
     * @param mensajesDisponibles mensajes esperando a que alguien los tome
     * @param mensajesEnProceso   tomados por un consumidor y aun sin confirmar
     * @param mensajesRetrasados  publicados con retardo, todavia no visibles
     */
    public record Estado(
            String nombre,
            String url,
            int mensajesDisponibles,
            int mensajesEnProceso,
            int mensajesRetrasados,
            int retencionSegundos,
            int visibilidadSegundos,
            int esperaLargaSegundos,
            String redrivePolicy,
            EstadoDlq dlq) {
    }

    /** Estado de la DLQ asociada, si la cola tiene una. */
    public record EstadoDlq(String nombre, int mensajesDisponibles, int retencionSegundos) {
    }

    /** Un mensaje leido de una DLQ, con el motivo por el que termino ahi. */
    public record MensajeEnDlq(
            String messageId,
            String idEvento,
            String intento,
            String causa,
            String colaOrigen,
            String cuerpo) {
    }

    /** Resultado de reprocesar una DLQ. */
    public record ResultadoReproceso(
            String colaDlq,
            String colaDestino,
            int reprocesados,
            List<MensajeEnDlq> mensajes) {
    }

    /** Resumen de la topologia declarada, con sus politicas y justificaciones. */
    public record TopologiaDeclarada(
            String topico,
            String topicoArn,
            List<ColaDeclarada> colas) {
    }

    /** Una cola de la topologia, con todo lo que se le aplico. */
    public record ColaDeclarada(
            String id,
            String nombre,
            String url,
            int retencionSegundos,
            int visibilidadSegundos,
            int esperaLargaSegundos,
            int maxReceiveCount,
            String justificacion,
            String dlqNombre,
            int dlqRetencionSegundos,
            String dlqJustificacion,
            String suscripcionArn,
            String patronEnrutamiento,
            Map<String, List<String>> filtro) {
    }
}
