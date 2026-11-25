package com.pedidos360.streaming.comun;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un cambio sobre un item del estado derivado, descrito sin depender de
 * DynamoDB.
 *
 * <p>Los dominios no escriben en la base: devuelven una lista de cambios y la
 * infraestructura los aplica junto con la marca de "evento procesado", en una
 * sola transaccion. Eso mantiene la logica de negocio libre de SDK (se prueba
 * sin nube) y hace que un evento duplicado no pueda contarse dos veces.
 *
 * <p>Cada item se identifica por una clave de particion ({@code pk}) y una de
 * orden ({@code sk}). Un mismo item no puede aparecer dos veces en los cambios
 * de un evento (DynamoDB no lo permite dentro de una transaccion), por eso un
 * cambio puede sumar y fijar atributos a la vez.
 *
 * @param sumar           contadores a incrementar (si no existen, parten en cero)
 * @param fijar           atributos a sobrescribir
 * @param fijarSiNoExiste atributos que solo se escriben si aun no tienen valor
 *                        (por ejemplo, "la primera vez que el pedido llego a
 *                        este estado")
 * @param horasDeVida     tiempo que vive el item antes de que el TTL lo borre
 */
public record CambioEstado(
        String pk,
        String sk,
        Map<String, Number> sumar,
        Map<String, Object> fijar,
        Map<String, Object> fijarSiNoExiste,
        long horasDeVida) {

    public static Constructor en(String pk, String sk) {
        return new Constructor(pk, sk);
    }

    /** Arma un cambio de forma legible: {@code CambioEstado.en(pk, sk).sumar("x", 1).build(24)}. */
    public static final class Constructor {
        private final String pk;
        private final String sk;
        private final Map<String, Number> sumar = new LinkedHashMap<>();
        private final Map<String, Object> fijar = new LinkedHashMap<>();
        private final Map<String, Object> fijarSiNoExiste = new LinkedHashMap<>();

        private Constructor(String pk, String sk) {
            this.pk = pk;
            this.sk = sk;
        }

        public Constructor sumar(String contador, Number valor) {
            sumar.merge(contador, valor, Constructor::suma);
            return this;
        }

        public Constructor fijar(String atributo, Object valor) {
            if (valor != null) {
                fijar.put(atributo, valor);
            }
            return this;
        }

        public Constructor fijarSiNoExiste(String atributo, Object valor) {
            if (valor != null) {
                fijarSiNoExiste.put(atributo, valor);
            }
            return this;
        }

        public CambioEstado build(long horasDeVida) {
            return new CambioEstado(pk, sk, Map.copyOf(sumar), Map.copyOf(fijar), Map.copyOf(fijarSiNoExiste),
                    horasDeVida);
        }

        private static Number suma(Number a, Number b) {
            return new java.math.BigDecimal(a.toString()).add(new java.math.BigDecimal(b.toString()));
        }
    }
}
