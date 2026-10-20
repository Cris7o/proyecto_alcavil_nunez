package com.pedidos360.mensajeria.comun;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Permite inducir una falla en un consumidor concreto, por dominio.
 *
 * <p>Existe para poder demostrar en vivo las dos rutas de error que exige la
 * evaluacion: el reintento de lo recuperable hasta agotarse, y la derivacion
 * inmediata de lo no recuperable. Arranca siempre en {@link ModoFalla#NINGUNA}
 * y se cambia por la API de administracion.
 */
@Component
public class SimuladorFallas {

    private static final Logger log = LoggerFactory.getLogger(SimuladorFallas.class);

    private final Map<String, ModoFalla> modoPorConsumidor = new ConcurrentHashMap<>();

    public ModoFalla modoDe(String idConsumidor) {
        return modoPorConsumidor.getOrDefault(idConsumidor, ModoFalla.NINGUNA);
    }

    public void establecer(String idConsumidor, ModoFalla modo) {
        modoPorConsumidor.put(idConsumidor, modo);
        log.warn("Modo de falla inducida del consumidor '{}' cambiado a {}", idConsumidor, modo);
    }

    public Map<String, ModoFalla> estado() {
        return Map.copyOf(modoPorConsumidor);
    }

    /**
     * Lanza la excepcion correspondiente si el consumidor tiene una falla
     * inducida activa. Se invoca antes de ejecutar la accion de dominio.
     */
    public void aplicarSiCorresponde(String idConsumidor) {
        switch (modoDe(idConsumidor)) {
            case RECUPERABLE -> throw new ErrorRecuperableException(
                    "Falla transitoria inducida en el consumidor '" + idConsumidor + "'");
            case NO_RECUPERABLE -> throw new ErrorNoRecuperableException(
                    "Falla permanente inducida en el consumidor '" + idConsumidor + "'");
            case NINGUNA -> {
                // sin falla inducida
            }
        }
    }
}
