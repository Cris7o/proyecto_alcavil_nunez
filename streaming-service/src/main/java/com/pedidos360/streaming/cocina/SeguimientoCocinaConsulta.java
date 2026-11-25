package com.pedidos360.streaming.cocina;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.pedidos360.streaming.comun.CalendarioNegocio;
import com.pedidos360.streaming.comun.RepositorioEstado;
import com.pedidos360.streaming.config.StreamingProperties;
import com.pedidos360.streaming.config.StreamingProperties.EstadoPedidoClave;

/**
 * Lectura del seguimiento de cocina: pedidos en curso, cuanto llevan en su
 * etapa actual, cuales estan atrasados y tiempos promedio por etapa.
 *
 * <p>"Atrasado" se calcula al leer, contra el reloj actual, con los umbrales de
 * {@code pedido360.streaming.cocina.minutos-maximos}: un pedido puede pasar a
 * atrasado sin que llegue ningun evento nuevo.
 */
@Service
public class SeguimientoCocinaConsulta {

    private static final Set<String> TERMINALES = Set.of("ENTREGADO", "CANCELADO");
    private static final int FINALIZADOS_A_MOSTRAR = 8;

    private final RepositorioEstado repositorio;
    private final CalendarioNegocio calendario;
    private final StreamingProperties propiedades;

    public SeguimientoCocinaConsulta(RepositorioEstado repositorio, CalendarioNegocio calendario,
            StreamingProperties propiedades) {
        this.repositorio = repositorio;
        this.calendario = calendario;
        this.propiedades = propiedades;
    }

    public SeguimientoCocina actual() {
        Instant ahora = calendario.ahora();
        List<PedidoEnSeguimiento> enCurso = new ArrayList<>();
        List<PedidoEnSeguimiento> finalizados = new ArrayList<>();

        for (Map<String, Object> item : repositorio.leerParticion(SeguimientoCocinaManejador.PK)) {
            PedidoEnSeguimiento pedido = aPedido(item, ahora);
            (TERMINALES.contains(pedido.estado()) ? finalizados : enCurso).add(pedido);
        }

        enCurso.sort(Comparator.comparing(PedidoEnSeguimiento::minutosEnEstado).reversed());
        finalizados.sort(Comparator.comparing(PedidoEnSeguimiento::actualizadoEn).reversed());

        List<PedidoEnSeguimiento> todos = new ArrayList<>(enCurso);
        todos.addAll(finalizados);

        return new SeguimientoCocina(
                enCurso.size(),
                enCurso.stream().filter(PedidoEnSeguimiento::atrasado).count(),
                promedio(todos, PedidoEnSeguimiento::minutosEspera),
                promedio(todos, PedidoEnSeguimiento::minutosPreparacion),
                promedio(todos, PedidoEnSeguimiento::minutosReparto),
                propiedades.getCocina().getMinutosMaximos(),
                enCurso,
                finalizados.subList(0, Math.min(FINALIZADOS_A_MOSTRAR, finalizados.size())));
    }

    private PedidoEnSeguimiento aPedido(Map<String, Object> item, Instant ahora) {
        String estado = (String) item.get("estado");
        Instant entradaEstado = instante(item, estado);
        long minutosEnEstado = entradaEstado == null ? 0 : Duration.between(entradaEstado, ahora).toMinutes();

        Integer maximo = maximoPara(estado);
        boolean atrasado = maximo != null && minutosEnEstado > maximo;

        return new PedidoEnSeguimiento(
                ((Number) item.get("pedidoId")).longValue(),
                estado,
                (String) item.get("actualizadoEn"),
                item.get("total") instanceof BigDecimal total ? total : BigDecimal.ZERO,
                minutosEnEstado,
                maximo,
                atrasado,
                minutosEntre(item, "PENDIENTE", "EN_PREPARACION"),
                minutosEntre(item, "EN_PREPARACION", "LISTO"),
                minutosEntre(item, "EN_REPARTO", "ENTREGADO"));
    }

    private Integer maximoPara(String estado) {
        try {
            return propiedades.getCocina().getMinutosMaximos().get(EstadoPedidoClave.valueOf(estado));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static Long minutosEntre(Map<String, Object> item, String desde, String hasta) {
        Instant inicio = instante(item, desde);
        Instant fin = instante(item, hasta);
        return inicio == null || fin == null ? null : Duration.between(inicio, fin).toMinutes();
    }

    private static Instant instante(Map<String, Object> item, String estado) {
        Object valor = item.get(SeguimientoCocinaManejador.PREFIJO_INSTANTE + estado);
        return valor instanceof String texto ? Instant.parse(texto) : null;
    }

    private static Double promedio(List<PedidoEnSeguimiento> pedidos,
            java.util.function.Function<PedidoEnSeguimiento, Long> etapa) {
        OptionalDouble promedio = pedidos.stream().map(etapa).filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue).average();
        return promedio.isPresent() ? Math.round(promedio.getAsDouble() * 10) / 10.0 : null;
    }

    public record SeguimientoCocina(
            int pedidosEnCurso,
            long pedidosAtrasados,
            Double promedioMinutosEspera,
            Double promedioMinutosPreparacion,
            Double promedioMinutosReparto,
            Map<EstadoPedidoClave, Integer> minutosMaximosPorEstado,
            List<PedidoEnSeguimiento> enCurso,
            List<PedidoEnSeguimiento> finalizadosRecientes) {
    }

    public record PedidoEnSeguimiento(
            long pedidoId,
            String estado,
            String actualizadoEn,
            BigDecimal total,
            long minutosEnEstado,
            Integer minutosMaximos,
            boolean atrasado,
            Long minutosEspera,
            Long minutosPreparacion,
            Long minutosReparto) {
    }
}
