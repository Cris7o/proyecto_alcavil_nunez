package com.pedidos360.admin.dto;

import java.util.List;

/** Contratos de la API de monitoreo. */
public final class MonitoreoDto {

    private MonitoreoDto() {
    }

    /**
     * Estado de una alarma sobre una DLQ.
     *
     * @param estado {@code OK} sin mensajes en la DLQ, {@code ALARM} hay
     *               mensajes sin entregar, {@code INSUFFICIENT_DATA} todavia no
     *               hay suficientes datos para decidir
     */
    public record Alarma(
            String nombre,
            String cola,
            String metrica,
            String estado,
            String razonDelEstado,
            double umbral,
            String comparacion,
            int periodoSegundos,
            String descripcion) {
    }

    /**
     * Las cuatro metricas que describen la salud de una cola.
     *
     * @param mensajesDisponibles  esperando a que alguien los tome. Si sube y no
     *                             baja, los consumidores no dan abasto o estan
     *                             detenidos
     * @param mensajesEnProceso    tomados y sin confirmar. Si se quedan altos,
     *                             hay consumidores que fallan y no confirman
     * @param antiguedadDelMasViejo segundos que lleva esperando el mensaje mas
     *                             antiguo. Es la metrica que mide el atraso real
     * @param mensajesEnDlq        mensajes que ya nadie pudo procesar. Cualquier
     *                             valor mayor que cero exige revisar
     */
    public record MetricasCola(
            String cola,
            String dlq,
            Double mensajesDisponibles,
            Double mensajesEnProceso,
            Double antiguedadDelMasViejoSegundos,
            Double mensajesEnDlq,
            String interpretacion) {
    }

    public record ResultadoDeclaracionAlarmas(int creadas, List<String> nombres) {
    }
}
