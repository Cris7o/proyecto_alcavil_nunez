package com.pedidos360.streaming.comun;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Permite inducir una falla en una aplicacion consumidora concreta.
 *
 * <p>Es un instrumento de demostracion, no una regla de negocio. Arranca
 * siempre en {@link ModoFalla#NINGUNA} y se cambia por la API de control, que
 * el microservicio administrador reenvia a todas las instancias.
 */
@Component
public class SimuladorFallas {

    private static final Logger log = LoggerFactory.getLogger(SimuladorFallas.class);

    /** Intentos que falla cada evento en el modo {@link ModoFalla#TRANSITORIA_RECUPERABLE}. */
    public static final int INTENTOS_FALLIDOS_ANTES_DE_RECUPERAR = 2;

    private final Map<String, ModoFalla> modoPorAplicacion = new ConcurrentHashMap<>();

    public ModoFalla modoDe(String aplicacion) {
        return modoPorAplicacion.getOrDefault(aplicacion, ModoFalla.NINGUNA);
    }

    public void establecer(String aplicacion, ModoFalla modo) {
        modoPorAplicacion.put(aplicacion, modo);
        log.warn("Falla inducida de la aplicacion {} cambiada a {}", aplicacion, modo);
    }

    public Map<String, ModoFalla> estado() {
        return Map.copyOf(modoPorAplicacion);
    }

    /**
     * Lanza la excepcion correspondiente si la aplicacion tiene una falla
     * inducida. Se invoca antes de la logica de dominio, en cada intento.
     */
    public void aplicarSiCorresponde(String aplicacion, int intento) {
        switch (modoDe(aplicacion)) {
            case TRANSITORIA_RECUPERABLE -> {
                if (intento <= INTENTOS_FALLIDOS_ANTES_DE_RECUPERAR) {
                    throw new ErrorTransitorioException(
                            "Falla transitoria inducida (se recupera en el intento "
                                    + (INTENTOS_FALLIDOS_ANTES_DE_RECUPERAR + 1) + ")");
                }
            }
            case TRANSITORIA_PERSISTENTE -> throw new ErrorTransitorioException(
                    "Falla transitoria inducida que no se recupera");
            case NO_RECUPERABLE -> throw new ErrorNoRecuperableException(
                    "Falla permanente inducida en la aplicacion " + aplicacion);
            case NINGUNA -> {
                // sin falla inducida
            }
        }
    }
}
