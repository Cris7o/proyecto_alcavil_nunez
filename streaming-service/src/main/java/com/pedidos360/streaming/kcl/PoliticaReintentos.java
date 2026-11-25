package com.pedidos360.streaming.kcl;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Reintentos con espera creciente (exponential backoff con jitter).
 *
 * <p>La espera del intento {@code n} es {@code base * 2^(n-1)}, con tope, mas
 * una variacion aleatoria de hasta 20 %. La variacion evita que varias
 * instancias que fallaron al mismo tiempo (por ejemplo, por un conflicto en la
 * misma fila de DynamoDB) vuelvan a chocar al reintentar todas juntas.
 *
 * <p>El numero de intentos es acotado a proposito: un evento con problemas no
 * puede retener el shard indefinidamente.
 */
public class PoliticaReintentos {

    private final int maxIntentos;
    private final long esperaBaseMs;
    private final long esperaMaximaMs;
    private final Espera espera;

    public PoliticaReintentos(int maxIntentos, long esperaBaseMs, long esperaMaximaMs) {
        this(maxIntentos, esperaBaseMs, esperaMaximaMs, Thread::sleep);
    }

    PoliticaReintentos(int maxIntentos, long esperaBaseMs, long esperaMaximaMs, Espera espera) {
        if (maxIntentos < 1) {
            throw new IllegalArgumentException("maxIntentos debe ser al menos 1");
        }
        this.maxIntentos = maxIntentos;
        this.esperaBaseMs = esperaBaseMs;
        this.esperaMaximaMs = esperaMaximaMs;
        this.espera = espera;
    }

    public int maxIntentos() {
        return maxIntentos;
    }

    public boolean quedanIntentos(int intentoActual) {
        return intentoActual < maxIntentos;
    }

    /** Milisegundos a esperar antes del intento siguiente a {@code intentoFallido}. */
    public long esperaTras(int intentoFallido) {
        long exponencial = esperaBaseMs * (1L << Math.min(intentoFallido - 1, 20));
        long conTope = Math.min(exponencial, esperaMaximaMs);
        long jitter = (long) (conTope * 0.2 * ThreadLocalRandom.current().nextDouble());
        return conTope + jitter;
    }

    /** Duerme la espera correspondiente. Devuelve los milisegundos esperados. */
    public long esperar(int intentoFallido) {
        long ms = esperaTras(intentoFallido);
        try {
            espera.dormir(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ms;
    }

    /** Permite reemplazar el sueno real en las pruebas. */
    @FunctionalInterface
    interface Espera {
        void dormir(long ms) throws InterruptedException;
    }
}
