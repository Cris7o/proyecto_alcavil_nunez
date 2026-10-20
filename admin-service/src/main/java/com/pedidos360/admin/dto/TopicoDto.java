package com.pedidos360.admin.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Contratos de la API de topicos y suscripciones. */
public final class TopicoDto {

    private TopicoDto() {
    }

    public record CrearTopico(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9_-]{1,256}",
                    message = "El nombre solo admite letras, numeros, guion y guion bajo")
            String nombre) {
    }

    public record Resumen(String nombre, String arn) {
    }

    /**
     * Peticion para suscribir una cola a un topico.
     *
     * @param filtro             vacio para fanout, con contenido para entrega
     *                           selectiva
     * @param rawMessageDelivery entrega el cuerpo tal cual, sin el envoltorio
     *                           JSON de SNS
     */
    public record CrearSuscripcion(
            @NotBlank String topicoNombre,
            @NotBlank String colaNombre,
            Map<String, List<String>> filtro,
            Boolean rawMessageDelivery) {
    }

    public record ActualizarFiltro(Map<String, List<String>> filtro) {
    }

    /** Una suscripcion con su patron de enrutamiento resuelto. */
    public record ResumenSuscripcion(
            String arn,
            String protocolo,
            String destino,
            String patronEnrutamiento,
            String filtro,
            String rawMessageDelivery) {
    }

    /**
     * Evento sintetico publicado en el topico para demostraciones.
     *
     * <p>Sirve para mostrar fanout y filtros sin tener que crear pedidos reales,
     * y para probar la tolerancia a duplicados repitiendo el mismo
     * {@code idEvento} dos veces.
     */
    public record EventoPrueba(
            @NotBlank String tipoEvento,
            String idEvento,
            Long pedidoId,
            String clienteNombre,
            String clienteEmail) {
    }

    /** Resultado de publicar un evento. */
    public record ResultadoPublicacion(
            String topicoArn,
            String messageId,
            String tipoEvento,
            String idEvento,
            String cuerpo) {
    }
}
