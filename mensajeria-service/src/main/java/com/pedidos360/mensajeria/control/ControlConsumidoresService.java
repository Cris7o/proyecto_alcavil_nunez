package com.pedidos360.mensajeria.control;

import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pedidos360.mensajeria.comun.ModoFalla;
import com.pedidos360.mensajeria.comun.SimuladorFallas;
import com.pedidos360.mensajeria.config.MensajeriaProperties;
import com.pedidos360.mensajeria.config.MensajeriaProperties.Cola;

import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;

/**
 * Pausa y reanuda consumidores de este proceso.
 *
 * <p>Spring Cloud AWS mantiene un contenedor por cada {@code @SqsListener},
 * identificado por el {@code id} de la anotacion. Detener el contenedor hace
 * que el consumidor deje de pedir mensajes, sin cerrar la aplicacion: los
 * mensajes se quedan en la cola acumulandose y, al reanudar, se procesan todos.
 *
 * <p>Pausar no pierde mensajes. Un mensaje solo desaparece de la cola cuando
 * alguien lo confirma, y un consumidor detenido no confirma nada.
 */
@Service
public class ControlConsumidoresService {

    private static final Logger log = LoggerFactory.getLogger(ControlConsumidoresService.class);

    private final MessageListenerContainerRegistry registro;
    private final MensajeriaProperties propiedades;
    private final SimuladorFallas simuladorFallas;

    public ControlConsumidoresService(MessageListenerContainerRegistry registro,
            MensajeriaProperties propiedades, SimuladorFallas simuladorFallas) {
        this.registro = registro;
        this.propiedades = propiedades;
        this.simuladorFallas = simuladorFallas;
    }

    public List<EstadoConsumidor> listar() {
        return propiedades.getColas().todas().stream().map(this::estadoDe).toList();
    }

    public EstadoConsumidor pausar(String idConsumidor) {
        MessageListenerContainer<?> contenedor = contenedorDe(idConsumidor);
        if (contenedor.isRunning()) {
            contenedor.stop();
            log.warn("Consumidor '{}' pausado. Los mensajes quedan en la cola.", idConsumidor);
        }
        return estadoDe(colaDe(idConsumidor));
    }

    public EstadoConsumidor reanudar(String idConsumidor) {
        MessageListenerContainer<?> contenedor = contenedorDe(idConsumidor);
        if (!contenedor.isRunning()) {
            contenedor.start();
            log.warn("Consumidor '{}' reanudado. Procesara lo acumulado.", idConsumidor);
        }
        return estadoDe(colaDe(idConsumidor));
    }

    public EstadoConsumidor establecerModoFalla(String idConsumidor, ModoFalla modo) {
        colaDe(idConsumidor);
        simuladorFallas.establecer(idConsumidor, modo);
        return estadoDe(colaDe(idConsumidor));
    }

    private EstadoConsumidor estadoDe(Cola cola) {
        MessageListenerContainer<?> contenedor = registro.getContainerById(cola.getId());
        boolean activo = contenedor != null && contenedor.isRunning();
        return new EstadoConsumidor(cola.getId(), cola.getNombre(), activo,
                simuladorFallas.modoDe(cola.getId()));
    }

    private Cola colaDe(String idConsumidor) {
        return propiedades.getColas().todas().stream()
                .filter(cola -> cola.getId().equals(idConsumidor))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "No existe un consumidor con id '" + idConsumidor + "'"));
    }

    private MessageListenerContainer<?> contenedorDe(String idConsumidor) {
        colaDe(idConsumidor);
        MessageListenerContainer<?> contenedor = registro.getContainerById(idConsumidor);
        if (contenedor == null) {
            throw new NoSuchElementException(
                    "El consumidor '" + idConsumidor + "' no tiene un contenedor registrado");
        }
        return contenedor;
    }
}
