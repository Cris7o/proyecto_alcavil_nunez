package com.pedidos360.streaming.tendencias;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pedidos360.streaming.comun.CalendarioNegocio;
import com.pedidos360.streaming.comun.RepositorioEstado;

/**
 * Lectura de tendencias: suma la hora actual y la anterior, para que la
 * ventana no quede vacia justo al cambiar de hora.
 */
@Service
public class TendenciasMenuConsulta {

    private static final int TOP = 5;

    private final RepositorioEstado repositorio;
    private final CalendarioNegocio calendario;

    public TendenciasMenuConsulta(RepositorioEstado repositorio, CalendarioNegocio calendario) {
        this.repositorio = repositorio;
        this.calendario = calendario;
    }

    public Tendencias ultimaHora() {
        Instant ahora = calendario.ahora();
        List<String> horas = List.of(calendario.diaYHora(ahora.minusSeconds(3600)), calendario.diaYHora(ahora));

        Map<String, Acumulado> productos = new HashMap<>();
        Map<String, Acumulado> busquedas = new HashMap<>();
        Map<String, Acumulado> categorias = new HashMap<>();
        long eventos = 0;

        for (String hora : horas) {
            for (Map<String, Object> item : repositorio.leerParticion(TendenciasMenuManejador.PREFIJO_HORA + hora)) {
                String sk = (String) item.get("sk");
                if (TendenciasMenuManejador.SK_RESUMEN.equals(sk)) {
                    eventos += numero(item, "eventos");
                } else if (sk.startsWith(TendenciasMenuManejador.PREFIJO_PRODUCTO)) {
                    productos.computeIfAbsent(sk, k -> new Acumulado((String) item.get("nombre")))
                            .sumar(numero(item, "agregados"), numero(item, "quitados"));
                } else if (sk.startsWith(TendenciasMenuManejador.PREFIJO_BUSQUEDA)) {
                    busquedas.computeIfAbsent(sk, k -> new Acumulado((String) item.get("termino")))
                            .sumar(numero(item, "veces"), 0);
                } else if (sk.startsWith(TendenciasMenuManejador.PREFIJO_CATEGORIA)) {
                    categorias.computeIfAbsent(sk, k -> new Acumulado((String) item.get("categoria")))
                            .sumar(numero(item, "veces"), 0);
                }
            }
        }

        return new Tendencias(horas, eventos, top(productos), top(busquedas), top(categorias));
    }

    private static List<Ranking> top(Map<String, Acumulado> acumulados) {
        List<Ranking> ranking = new ArrayList<>();
        acumulados.values().forEach(a -> ranking.add(new Ranking(a.etiqueta, a.principal, a.secundario)));
        ranking.sort(Comparator.comparingLong(Ranking::veces).reversed());
        return ranking.subList(0, Math.min(TOP, ranking.size()));
    }

    private static long numero(Map<String, Object> item, String atributo) {
        Object valor = item.get(atributo);
        return valor instanceof BigDecimal n ? n.longValue() : 0;
    }

    private static final class Acumulado {
        private final String etiqueta;
        private long principal;
        private long secundario;

        private Acumulado(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        private void sumar(long principal, long secundario) {
            this.principal += principal;
            this.secundario += secundario;
        }
    }

    public record Tendencias(List<String> horasConsideradas, long eventos, List<Ranking> productosMasAgregados,
            List<Ranking> busquedas, List<Ranking> categorias) {
    }

    /** Para productos, {@code veces} son agregados y {@code quitados} los quitados del carrito. */
    public record Ranking(String etiqueta, long veces, long quitados) {
    }
}
