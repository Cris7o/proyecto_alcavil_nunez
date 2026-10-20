package com.pedidos360.mensajeria.comun;

/**
 * Falla transitoria: tiene sentido volver a intentar.
 *
 * <p>Ejemplos: el proveedor de correo no responde, la base de datos esta
 * saturada, se agoto un tiempo de espera. El mensaje no se confirma, SQS lo
 * vuelve a entregar cuando expira el visibility timeout, y si se agotan los
 * intentos termina en la DLQ.
 */
public class ErrorRecuperableException extends RuntimeException {

    public ErrorRecuperableException(String mensaje) {
        super(mensaje);
    }

    public ErrorRecuperableException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
