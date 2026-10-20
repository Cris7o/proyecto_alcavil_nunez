package com.pedidos360.mensajeria.documentos;

import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.pedidos360.mensajeria.comun.ProcesadorEventos;
import com.pedidos360.mensajeria.config.MensajeriaProperties;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;

/**
 * Consumidor del dominio de documentos.
 *
 * <p>Su cola esta suscrita con un filtro mas estrecho que la de notificaciones:
 * solo le interesa cuando nace el pedido (comanda de cocina) y cuando se
 * entrega (boleta). Dos filtros distintos sobre el mismo topico es lo que hace
 * que esto sea entrega selectiva y no fanout.
 */
@Component
public class DocumentosConsumer {

    /** Debe coincidir con {@code pedido360.mensajeria.colas.documentos.id}. */
    public static final String ID_CONSUMIDOR = "documentos";

    private final ProcesadorEventos procesador;
    private final DocumentoService documentoService;
    private final MensajeriaProperties propiedades;

    public DocumentosConsumer(ProcesadorEventos procesador, DocumentoService documentoService,
            MensajeriaProperties propiedades) {
        this.procesador = procesador;
        this.documentoService = documentoService;
        this.propiedades = propiedades;
    }

    @SqsListener(
            value = "${pedido360.mensajeria.colas.documentos.nombre}",
            id = ID_CONSUMIDOR,
            acknowledgementMode = "MANUAL")
    public void recibir(Message<String> mensaje, Acknowledgement confirmacion) {
        procesador.procesar(
                propiedades.getColas().getDocumentos(),
                mensaje,
                confirmacion,
                documentoService::generar);
    }
}
