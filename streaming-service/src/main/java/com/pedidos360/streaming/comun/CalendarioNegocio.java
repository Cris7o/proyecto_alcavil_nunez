package com.pedidos360.streaming.comun;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Agrupa instantes en dias y horas del local, con la zona horaria de la
 * configuracion. Un pedido a las 23:30 de Santiago pertenece a ese dia, no al
 * siguiente como diria UTC.
 */
public class CalendarioNegocio {

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH");
    private static final DateTimeFormatter DIA_Y_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");

    private final ZoneId zona;
    private final Clock reloj;

    public CalendarioNegocio(String zona) {
        this(ZoneId.of(zona), Clock.systemUTC());
    }

    public CalendarioNegocio(ZoneId zona, Clock reloj) {
        this.zona = zona;
        this.reloj = reloj;
    }

    public String dia(Instant instante) {
        return DIA.format(instante.atZone(zona));
    }

    public String hora(Instant instante) {
        return HORA.format(instante.atZone(zona));
    }

    public String diaYHora(Instant instante) {
        return DIA_Y_HORA.format(instante.atZone(zona));
    }

    public Instant ahora() {
        return reloj.instant();
    }
}
