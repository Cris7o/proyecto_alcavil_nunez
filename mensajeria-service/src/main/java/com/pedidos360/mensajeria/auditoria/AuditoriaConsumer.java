package com.pedidos360.mensajeria.auditoria;

import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.pedidos360.mensajeria.comun.ProcesadorEventos;
import com.pedidos360.mensajeria.config.MensajeriaProperties;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;

/**
 * Consumidor del dominio de auditoria.
 *
 * <p>Es el consumidor que se usa para demostrar disponibilidad: al levantar dos
 * instancias de este microservicio, las dos compiten por la misma cola y SQS
 * reparte los mensajes entre ellas. Al detener una, la otra sigue tomando todo
 * sin que se pierda nada, porque un mensaje no se borra de la cola hasta que
 * alguien lo confirma.
 */
@Component
public class AuditoriaConsumer {

    /** Debe coincidir con {@code pedido360.mensajeria.colas.auditoria.id}. */
    public static final String ID_CONSUMIDOR = "auditoria";

    private final ProcesadorEventos procesador;
    private final AuditoriaService auditoriaService;
    private final MensajeriaProperties propiedades;

    public AuditoriaConsumer(ProcesadorEventos procesador, AuditoriaService auditoriaService,
            MensajeriaProperties propiedades) {
        this.procesador = procesador;
        this.auditoriaService = auditoriaService;
        this.propiedades = propiedades;
    }

    @SqsListener(
            value = "${pedido360.mensajeria.colas.auditoria.nombre}",
            id = ID_CONSUMIDOR,
            acknowledgementMode = "MANUAL")
    public void recibir(Message<String> mensaje, Acknowledgement confirmacion) {
        procesador.procesar(
                propiedades.getColas().getAuditoria(),
                mensaje,
                confirmacion,
                auditoriaService::registrar);
    }
}
