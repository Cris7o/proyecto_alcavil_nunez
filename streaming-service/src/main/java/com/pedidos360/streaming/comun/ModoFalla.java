package com.pedidos360.streaming.comun;

/**
 * Falla inducida en una aplicacion consumidora, para demostrar en vivo las
 * rutas de error sin corromper datos ni apagar servicios.
 */
public enum ModoFalla {

    /** Comportamiento normal. */
    NINGUNA,

    /**
     * Falla transitoria que se resuelve sola: los dos primeros intentos de cada
     * evento fallan y el tercero funciona. Muestra el reintento con espera
     * creciente terminando bien.
     */
    TRANSITORIA_RECUPERABLE,

    /**
     * Falla transitoria que no se resuelve: se agotan los intentos y el evento
     * va al stream de fallidos con causa REINTENTOS_AGOTADOS.
     */
    TRANSITORIA_PERSISTENTE,

    /** Falla permanente: el evento va al stream de fallidos en el primer intento. */
    NO_RECUPERABLE
}
