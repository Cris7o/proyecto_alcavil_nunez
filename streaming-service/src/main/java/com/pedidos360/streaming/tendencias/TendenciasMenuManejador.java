package com.pedidos360.streaming.tendencias;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.pedidos360.streaming.comun.CalendarioNegocio;
import com.pedidos360.streaming.comun.CambioEstado;
import com.pedidos360.streaming.comun.ErrorNoRecuperableException;
import com.pedidos360.streaming.comun.ManejadorDominio;
import com.pedidos360.streaming.eventos.EventoActividad;

/**
 * Dominio de tendencias: que productos se agregan al carrito, que se busca y
 * que categorias se filtran, por hora.
 *
 * <p>Aplicacion consumidora {@code pedido360-tendencias-menu}, sobre el stream
 * de actividad. Es el flujo de mayor volumen y menor valor individual del
 * sistema: perder la tendencia de hace dos dias no importa, por eso el stream
 * tiene la retencion minima (24 h) y estos contadores viven solo 48 h.
 */
@Component
public class TendenciasMenuManejador implements ManejadorDominio<EventoActividad> {

    public static final String DOMINIO = "tendencias";

    static final long HORAS_DE_VIDA = 48;

    static final String PREFIJO_HORA = "TENDENCIAS#";
    static final String SK_RESUMEN = "RESUMEN";
    static final String PREFIJO_PRODUCTO = "PRODUCTO#";
    static final String PREFIJO_BUSQUEDA = "BUSQUEDA#";
    static final String PREFIJO_CATEGORIA = "CATEGORIA#";

    /** Tolerancia al reloj del navegador: un evento "del futuro" esta mal fechado. */
    private static final Duration DESFASE_MAXIMO = Duration.ofMinutes(5);

    private final CalendarioNegocio calendario;

    public TendenciasMenuManejador(CalendarioNegocio calendario) {
        this.calendario = calendario;
    }

    @Override
    public String dominio() {
        return DOMINIO;
    }

    @Override
    public Class<EventoActividad> tipoEvento() {
        return EventoActividad.class;
    }

    @Override
    public String idEvento(EventoActividad evento) {
        return evento.idEvento();
    }

    @Override
    public List<CambioEstado> procesar(EventoActividad evento) {
        if (evento.ocurridoEn().isAfter(calendario.ahora().plus(DESFASE_MAXIMO))) {
            throw new ErrorNoRecuperableException("El evento esta fechado en el futuro (" + evento.ocurridoEn()
                    + "): reloj del cliente desajustado");
        }

        String pk = PREFIJO_HORA + calendario.diaYHora(evento.ocurridoEn());
        CambioEstado resumen = CambioEstado.en(pk, SK_RESUMEN)
                .sumar("eventos", 1)
                .sumar(evento.tipo().name(), 1)
                .build(HORAS_DE_VIDA);

        CambioEstado detalle = switch (evento.tipo()) {
            case PRODUCTO_AGREGADO -> producto(pk, evento).sumar("agregados", 1).build(HORAS_DE_VIDA);
            case PRODUCTO_QUITADO -> producto(pk, evento).sumar("quitados", 1).build(HORAS_DE_VIDA);
            case BUSQUEDA -> {
                String termino = normalizar(evento.termino(), "BUSQUEDA", "termino");
                yield CambioEstado.en(pk, PREFIJO_BUSQUEDA + termino)
                        .fijar("termino", termino)
                        .sumar("veces", 1)
                        .build(HORAS_DE_VIDA);
            }
            case CATEGORIA_FILTRADA -> {
                String categoria = normalizar(evento.categoria(), "CATEGORIA_FILTRADA", "categoria");
                yield CambioEstado.en(pk, PREFIJO_CATEGORIA + categoria)
                        .fijar("categoria", categoria)
                        .sumar("veces", 1)
                        .build(HORAS_DE_VIDA);
            }
        };

        return List.of(resumen, detalle);
    }

    private CambioEstado.Constructor producto(String pk, EventoActividad evento) {
        if (evento.productoId() == null || evento.productoId() <= 0) {
            throw new ErrorNoRecuperableException(
                    "Un evento " + evento.tipo() + " debe indicar productoId");
        }
        return CambioEstado.en(pk, PREFIJO_PRODUCTO + evento.productoId())
                .fijar("productoId", evento.productoId())
                .fijar("nombre", evento.productoNombre());
    }

    private static String normalizar(String valor, String tipo, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ErrorNoRecuperableException("Un evento " + tipo + " debe indicar " + campo);
        }
        return valor.trim().toLowerCase(Locale.ROOT);
    }
}
