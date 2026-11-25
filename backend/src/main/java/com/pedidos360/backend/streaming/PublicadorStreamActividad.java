package com.pedidos360.backend.streaming;

import java.util.List;
import java.util.Map;

/** Publicacion por lote de la actividad del cliente. */
public interface PublicadorStreamActividad {

    ResultadoLote publicarLote(List<EventoActividad> eventos);

    /**
     * Resultado de un envio por lote.
     *
     * @param recibidos   eventos que llegaron
     * @param publicados  eventos que Kinesis acepto
     * @param rechazados  eventos que siguieron rechazados tras todos los intentos
     * @param intentos    llamadas a PutRecords que hicieron falta
     * @param erroresPorCodigo cuantos registros rechazo Kinesis por cada codigo
     *                    de error (por ejemplo ProvisionedThroughputExceededException)
     */
    record ResultadoLote(int recibidos, int publicados, int rechazados, int intentos,
            Map<String, Integer> erroresPorCodigo) {
    }
}
