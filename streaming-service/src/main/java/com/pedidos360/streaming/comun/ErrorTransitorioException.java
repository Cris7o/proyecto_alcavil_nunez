package com.pedidos360.streaming.comun;

/**
 * Falla pasajera (una dependencia ocupada o caida un momento). Se reintenta
 * con espera creciente; si persiste despues del ultimo intento, el evento va al
 * stream de fallidos.
 */
public class ErrorTransitorioException extends RuntimeException {

    public ErrorTransitorioException(String mensaje) {
        super(mensaje);
    }

    public ErrorTransitorioException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
