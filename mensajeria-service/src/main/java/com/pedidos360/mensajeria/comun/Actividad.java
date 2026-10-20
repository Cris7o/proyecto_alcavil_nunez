package com.pedidos360.mensajeria.comun;

import java.time.Instant;

/**
 * Una linea de lo que le paso a un mensaje.
 *
 * <p>Es la misma informacion que queda en el log, pero estructurada para poder
 * mostrarla en el panel de administracion. El log sirve para diagnosticar
 * despues; esto sirve para ver lo que esta pasando ahora.
 *
 * @param instancia  que instancia del microservicio lo proceso. Es el dato que
 *                   permite ver como SQS reparte el trabajo entre dos
 *                   consumidores sobre la misma cola
 * @param consumidor dominio que lo proceso (notificaciones, documentos, auditoria)
 * @param resultado  en que termino
 * @param idEvento   identificador del evento, el mismo con el que se detectan
 *                   duplicados
 * @param intento    numero de recepcion, que SQS incrementa en cada entrega
 * @param detalle    la causa cuando algo fallo, o un resumen cuando salio bien
 */
public record Actividad(
        Instant momento,
        String instancia,
        String consumidor,
        Resultado resultado,
        String idEvento,
        String tipoEvento,
        Long pedidoId,
        int intento,
        String detalle) {

    /** En que puede terminar el procesamiento de un mensaje. */
    public enum Resultado {

        /** Procesado y confirmado. */
        PROCESADO,

        /** Ya se habia procesado antes: se confirma sin volver a ejecutar. */
        DUPLICADO,

        /** Falla transitoria: no se confirma, SQS lo reentrega. */
        REINTENTO,

        /** Se derivo a la DLQ con su causa. */
        DERIVADO_A_DLQ
    }
}
