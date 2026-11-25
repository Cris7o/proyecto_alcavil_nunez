package com.pedidos360.streaming.comun;

import java.util.List;

/**
 * Logica de negocio de una aplicacion consumidora.
 *
 * <p>Recibe un evento ya leido y validado y decide que cambios produce. No
 * conoce Kinesis, la KCL, los shards ni los checkpoints: toda esa
 * infraestructura vive en el paquete {@code kcl}. Por eso cada dominio se
 * prueba con un test unitario simple, sin nube.
 *
 * @param <T> tipo del evento (DTO) que procesa
 */
public interface ManejadorDominio<T> {

    /** Dominio funcional, el mismo que declara la aplicacion en la configuracion. */
    String dominio();

    /** Clase del DTO en que se deserializa cada registro del stream. */
    Class<T> tipoEvento();

    /** Identificador unico del evento, para idempotencia y logs. */
    String idEvento(T evento);

    /**
     * Cambios de estado que produce el evento.
     *
     * @throws ErrorNoRecuperableException si el evento no tiene sentido para el
     *         negocio (va directo al stream de fallidos).
     */
    List<CambioEstado> procesar(T evento);
}
