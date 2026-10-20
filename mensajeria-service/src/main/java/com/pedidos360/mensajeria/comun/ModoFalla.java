package com.pedidos360.mensajeria.comun;

/**
 * Modo de falla inducida para demostrar el manejo de errores en vivo.
 *
 * <p>Es un instrumento de prueba, no una regla de negocio: permite provocar
 * desde la API de administracion los dos caminos de error sin tener que
 * corromper datos reales ni apagar servicios de verdad.
 */
public enum ModoFalla {

    /** Comportamiento normal. */
    NINGUNA,

    /**
     * Simula una falla transitoria. El mensaje se reintenta hasta agotar
     * {@code maxReintentos} y recien entonces se deriva a la DLQ.
     */
    RECUPERABLE,

    /**
     * Simula una falla permanente. El mensaje se deriva a la DLQ en el primer
     * intento, sin reintentos.
     */
    NO_RECUPERABLE
}
