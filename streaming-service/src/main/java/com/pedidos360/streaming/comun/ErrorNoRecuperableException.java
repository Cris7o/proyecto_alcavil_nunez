package com.pedidos360.streaming.comun;

/**
 * El evento no se puede procesar y reintentar no cambiaria el resultado (un
 * dato que no tiene sentido para el negocio, por ejemplo). Va directo al stream
 * de fallidos, sin reintentos.
 */
public class ErrorNoRecuperableException extends RuntimeException {

    public ErrorNoRecuperableException(String mensaje) {
        super(mensaje);
    }
}
