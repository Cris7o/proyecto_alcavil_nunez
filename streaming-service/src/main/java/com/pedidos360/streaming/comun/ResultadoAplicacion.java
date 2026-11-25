package com.pedidos360.streaming.comun;

/** Que paso al aplicar los cambios de un evento. */
public enum ResultadoAplicacion {
    /** Primera vez que se ve el evento: los cambios quedaron aplicados. */
    APLICADO,
    /** El idEvento ya estaba procesado por esta aplicacion: no se aplico nada. */
    DUPLICADO
}
