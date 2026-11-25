package com.pedidos360.streaming.kcl;

/** Como termino el procesamiento de un registro. Ninguna ruta detiene el shard. */
public record ResultadoRegistro(Ruta ruta, String idEvento, int intentos, String detalle) {

    public enum Ruta {
        /** Se aplico el evento. */
        PROCESADO,
        /** El idEvento ya se habia procesado: se descarto sin repetir la accion. */
        DUPLICADO,
        /** No se pudo procesar: quedo en el stream de fallidos con su causa. */
        DERIVADO_A_FALLIDOS
    }
}
