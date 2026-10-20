package com.pedidos360.mensajeria.comun;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.pedidos360.mensajeria.config.MensajeriaProperties.Cola;

import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import tools.jackson.databind.ObjectMapper;

/**
 * Politica de confirmacion y manejo de errores, en un solo lugar.
 *
 * <p>Los tres consumidores delegan aqui para que la decision de cuando
 * confirmar, cuando reintentar y cuando rendirse sea identica en los tres
 * dominios y se pueda razonar leyendo una sola clase. Cada consumidor solo
 * aporta su accion de dominio.
 *
 * <p>El modo de confirmacion es {@code MANUAL}: ningun mensaje se borra de la
 * cola salvo que este codigo llame explicitamente a
 * {@link Acknowledgement#acknowledge()}. Con el modo automatico por defecto, un
 * mensaje se confirmaria solo por no haber lanzado excepcion, y se perderia el
 * control sobre que se reintenta.
 *
 * <p>Las cuatro rutas posibles:
 * <ol>
 * <li><b>Exito</b>: se marca el evento como procesado y se confirma.
 * <li><b>Duplicado</b>: ya se proceso antes, se confirma sin volver a ejecutar.
 * <li><b>Error no recuperable</b>: se deriva a la DLQ con la causa y se
 * confirma. Reintentar no cambiaria nada.
 * <li><b>Error recuperable</b>: si quedan intentos, <em>no</em> se confirma y
 * SQS lo reentrega al expirar el visibility timeout. Si se agotaron, se deriva
 * a la DLQ con la causa y se confirma.
 * </ol>
 */
@Component
public class ProcesadorEventos {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorEventos.class);

    private final ObjectMapper objectMapper;
    private final RegistroEventosProcesados registroProcesados;
    private final RegistroMensajesNoEntregados registroNoEntregados;
    private final RegistroActividad registroActividad;
    private final SimuladorFallas simuladorFallas;

    public ProcesadorEventos(ObjectMapper objectMapper,
            RegistroEventosProcesados registroProcesados,
            RegistroMensajesNoEntregados registroNoEntregados,
            RegistroActividad registroActividad,
            SimuladorFallas simuladorFallas) {
        this.objectMapper = objectMapper;
        this.registroProcesados = registroProcesados;
        this.registroNoEntregados = registroNoEntregados;
        this.registroActividad = registroActividad;
        this.simuladorFallas = simuladorFallas;
    }

    public void procesar(Cola cola, Message<String> mensaje, Acknowledgement confirmacion,
            Consumer<EventoPedido> accionDeDominio) {

        String cuerpo = mensaje.getPayload();
        int intento = numeroDeIntento(mensaje);

        // 1. El cuerpo tiene que ser legible. Si no lo es, reintentar es inutil.
        EventoPedido evento;
        try {
            evento = objectMapper.readValue(cuerpo, EventoPedido.class);
        } catch (Exception e) {
            String causa = "Cuerpo ilegible: " + e.getMessage();
            registroNoEntregados.derivar(cola.getDlq(), cola.getNombre(), null, intento, causa, cuerpo);
            registroActividad.registrar(cola.getId(), Actividad.Resultado.DERIVADO_A_DLQ,
                    null, null, null, intento, causa);
            confirmacion.acknowledge();
            return;
        }

        if (evento.idEvento() == null || evento.tipo() == null) {
            String causa = "Evento incompleto: falta idEvento o tipo";
            registroNoEntregados.derivar(cola.getDlq(), cola.getNombre(), evento.idEvento(), intento,
                    causa, cuerpo);
            registroActividad.registrar(cola.getId(), Actividad.Resultado.DERIVADO_A_DLQ,
                    evento.idEvento(), null, evento.pedidoId(), intento, causa);
            confirmacion.acknowledge();
            return;
        }

        // 2. Tolerancia a duplicados. SQS entrega al menos una vez.
        if (registroProcesados.yaFueProcesado(evento.idEvento())) {
            log.info("[{}] Duplicado descartado | idEvento={} | tipo={} | intento={}",
                    cola.getId(), evento.idEvento(), evento.tipo(), intento);
            registroActividad.registrar(cola.getId(), Actividad.Resultado.DUPLICADO,
                    evento.idEvento(), evento.tipo().name(), evento.pedidoId(), intento,
                    "Ya se habia procesado este idEvento, se descarta sin repetir la accion");
            confirmacion.acknowledge();
            return;
        }

        // 3. Procesamiento y rutas de error.
        try {
            simuladorFallas.aplicarSiCorresponde(cola.getId());
            accionDeDominio.accept(evento);

            registroProcesados.marcarProcesado(evento.idEvento());
            confirmacion.acknowledge();

            log.info("[{}] Procesado y confirmado | idEvento={} | tipo={} | pedidoId={} | intento={}",
                    cola.getId(), evento.idEvento(), evento.tipo(), evento.pedidoId(), intento);
            registroActividad.registrar(cola.getId(), Actividad.Resultado.PROCESADO,
                    evento.idEvento(), evento.tipo().name(), evento.pedidoId(), intento,
                    "Procesado y confirmado");

        } catch (ErrorNoRecuperableException e) {
            String causa = "No recuperable: " + e.getMessage();
            registroNoEntregados.derivar(cola.getDlq(), cola.getNombre(), evento.idEvento(), intento,
                    causa, cuerpo);
            registroActividad.registrar(cola.getId(), Actividad.Resultado.DERIVADO_A_DLQ,
                    evento.idEvento(), evento.tipo().name(), evento.pedidoId(), intento, causa);
            confirmacion.acknowledge();

        } catch (Exception e) {
            // Todo lo demas se trata como transitorio: es mas seguro reintentar
            // un error desconocido que descartarlo.
            if (intento >= cola.getMaxReintentos()) {
                String causa = "Reintentos agotados (%d de %d): %s"
                        .formatted(intento, cola.getMaxReintentos(), e.getMessage());
                registroNoEntregados.derivar(cola.getDlq(), cola.getNombre(), evento.idEvento(),
                        intento, causa, cuerpo);
                registroActividad.registrar(cola.getId(), Actividad.Resultado.DERIVADO_A_DLQ,
                        evento.idEvento(), evento.tipo().name(), evento.pedidoId(), intento, causa);
                confirmacion.acknowledge();
            } else {
                // Sin confirmar a proposito: SQS lo reentrega al expirar el
                // visibility timeout.
                log.warn("[{}] Error recuperable, se reintentara | idEvento={} | intento={} de {} | causa={}",
                        cola.getId(), evento.idEvento(), intento, cola.getMaxReintentos(), e.getMessage());
                registroActividad.registrar(cola.getId(), Actividad.Resultado.REINTENTO,
                        evento.idEvento(), evento.tipo().name(), evento.pedidoId(), intento,
                        "Intento %d de %d: %s".formatted(intento, cola.getMaxReintentos(), e.getMessage()));
            }
        }
    }

    /**
     * Numero de recepcion del mensaje, que SQS incrementa en cada entrega. Es
     * el "intento" que se registra en los logs y en la DLQ.
     */
    private int numeroDeIntento(Message<String> mensaje) {
        Object valor = mensaje.getHeaders()
                .get(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT);
        if (valor == null) {
            return 1;
        }
        try {
            return Integer.parseInt(valor.toString());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
