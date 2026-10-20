package com.pedidos360.mensajeria.comun;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.awspring.cloud.sqs.operations.SqsTemplate;

/**
 * Integracion de los consumidores con las colas de mensajes muertos.
 *
 * <p>Hace dos cosas, y las dos son necesarias:
 *
 * <ol>
 * <li>Deja el mensaje en la DLQ <em>con la causa adjunta</em> como atributo. La
 * Redrive Policy de AWS tambien mueve mensajes a la DLQ, pero lo hace sin
 * explicar por que fallaron; derivando nosotros, el mensaje queda en la DLQ
 * acompanado del motivo, del intento en que se rindio y de la cola de origen.
 * <li>Registra en el log el id del evento, el intento y la causa, que es
 * exactamente la informacion que se necesita para diagnosticar sin tener que
 * abrir la consola de AWS.
 * </ol>
 *
 * <p>A proposito no existe un {@code @SqsListener} sobre las DLQ: un consumidor
 * sobre la DLQ borraria los mensajes al leerlos, y entonces no quedarian
 * visibles en la consola ni dispararian la alarma de CloudWatch. La DLQ se
 * inspecciona y se reprocesa desde el microservicio administrador, bajo
 * demanda.
 */
@Component
public class RegistroMensajesNoEntregados {

    private static final Logger log = LoggerFactory.getLogger(RegistroMensajesNoEntregados.class);

    /** Atributos con los que se enriquece el mensaje al dejarlo en la DLQ. */
    public static final String ATRIBUTO_CAUSA = "causa";
    public static final String ATRIBUTO_INTENTO = "intento";
    public static final String ATRIBUTO_COLA_ORIGEN = "colaOrigen";
    public static final String ATRIBUTO_ID_EVENTO = "idEvento";

    private final SqsTemplate sqsTemplate;

    public RegistroMensajesNoEntregados(SqsTemplate sqsTemplate) {
        this.sqsTemplate = sqsTemplate;
    }

    /**
     * Deriva el mensaje a la DLQ y lo registra.
     *
     * @param nombreDlq    cola de mensajes muertos destino
     * @param colaOrigen   cola en la que fallo el procesamiento
     * @param idEvento     identificador del evento, o {@code null} si el cuerpo
     *                     no se pudo leer
     * @param intento      numero de recepcion en que se dio por perdido
     * @param causa        motivo del descarte
     * @param cuerpoOriginal cuerpo tal cual llego, sin reinterpretar
     */
    public void derivar(String nombreDlq, String colaOrigen, String idEvento, int intento,
            String causa, String cuerpoOriginal) {

        log.error("Mensaje no entregado -> DLQ {} | idEvento={} | intento={} | causa={}",
                nombreDlq, idEvento, intento, causa);

        try {
            sqsTemplate.send(opciones -> opciones
                    .queue(nombreDlq)
                    .payload(cuerpoOriginal)
                    .headers(Map.of(
                            ATRIBUTO_ID_EVENTO, idEvento == null ? "desconocido" : idEvento,
                            ATRIBUTO_INTENTO, String.valueOf(intento),
                            ATRIBUTO_CAUSA, recortar(causa),
                            ATRIBUTO_COLA_ORIGEN, colaOrigen)));

        } catch (Exception e) {
            // Si ni la DLQ responde, no se confirma nada mas: queda el log y la
            // Redrive Policy de AWS actuara como red de seguridad.
            log.error("No se pudo escribir en la DLQ {} | idEvento={} | causa original={}",
                    nombreDlq, idEvento, causa, e);
            throw new ErrorRecuperableException("La DLQ no esta disponible", e);
        }
    }

    /** SQS limita los atributos de mensaje; una causa muy larga se recorta. */
    private String recortar(String causa) {
        if (causa == null) {
            return "sin causa";
        }
        return causa.length() <= 240 ? causa : causa.substring(0, 237) + "...";
    }
}
