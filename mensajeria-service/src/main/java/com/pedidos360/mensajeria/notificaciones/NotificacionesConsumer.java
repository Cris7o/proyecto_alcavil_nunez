package com.pedidos360.mensajeria.notificaciones;

import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.pedidos360.mensajeria.comun.ProcesadorEventos;
import com.pedidos360.mensajeria.config.MensajeriaProperties;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;

/**
 * Consumidor del dominio de notificaciones.
 *
 * <p>El nombre de la cola se resuelve desde la configuracion centralizada, no
 * esta escrito aqui. El {@code id} es el identificador por el que el
 * microservicio administrador pausa y reanuda este consumidor.
 *
 * <p>La cola esta suscrita al topico con un filtro: solo recibe los eventos que
 * le importan al cliente (creado, listo, entregado y cancelado). Los estados
 * intermedios nunca llegan a esta cola, y esa es la demostracion del patron de
 * entrega selectiva.
 */
@Component
public class NotificacionesConsumer {

    /** Debe coincidir con {@code pedido360.mensajeria.colas.notificaciones.id}. */
    public static final String ID_CONSUMIDOR = "notificaciones";

    private final ProcesadorEventos procesador;
    private final NotificacionService notificacionService;
    private final MensajeriaProperties propiedades;

    public NotificacionesConsumer(ProcesadorEventos procesador,
            NotificacionService notificacionService, MensajeriaProperties propiedades) {
        this.procesador = procesador;
        this.notificacionService = notificacionService;
        this.propiedades = propiedades;
    }

    @SqsListener(
            value = "${pedido360.mensajeria.colas.notificaciones.nombre}",
            id = ID_CONSUMIDOR,
            acknowledgementMode = "MANUAL")
    public void recibir(Message<String> mensaje, Acknowledgement confirmacion) {
        procesador.procesar(
                propiedades.getColas().getNotificaciones(),
                mensaje,
                confirmacion,
                notificacionService::notificar);
    }
}
