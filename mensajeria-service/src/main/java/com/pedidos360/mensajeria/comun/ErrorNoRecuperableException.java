package com.pedidos360.mensajeria.comun;

/**
 * Falla permanente: reintentar daria exactamente el mismo resultado.
 *
 * <p>Ejemplos: el mensaje no es JSON valido, falta el correo del cliente, el
 * tipo de evento no existe. El mensaje se deriva a la DLQ de inmediato, sin
 * gastar reintentos, y se confirma para liberar la cola.
 */
public class ErrorNoRecuperableException extends RuntimeException {

    public ErrorNoRecuperableException(String mensaje) {
        super(mensaje);
    }

    public ErrorNoRecuperableException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
