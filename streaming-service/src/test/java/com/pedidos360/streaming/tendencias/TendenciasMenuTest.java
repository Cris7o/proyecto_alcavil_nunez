package com.pedidos360.streaming.tendencias;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.pedidos360.streaming.Eventos;
import com.pedidos360.streaming.RepositorioEnMemoria;
import com.pedidos360.streaming.comun.CalendarioNegocio;
import com.pedidos360.streaming.comun.ErrorNoRecuperableException;
import com.pedidos360.streaming.eventos.EventoActividad;
import com.pedidos360.streaming.eventos.TipoActividad;

class TendenciasMenuTest {

    private final CalendarioNegocio calendario = new CalendarioNegocio(ZoneId.of("America/Santiago"),
            Clock.fixed(Instant.parse("2026-11-24T18:45:00Z"), ZoneOffset.UTC));
    private final TendenciasMenuManejador manejador = new TendenciasMenuManejador(calendario);
    private final RepositorioEnMemoria repositorio = new RepositorioEnMemoria();

    @Test
    void rankeaLoMasAgregadoYLasBusquedasNormalizadas() {
        aplicar(Eventos.actividad(TipoActividad.PRODUCTO_AGREGADO, 10L, null));
        aplicar(Eventos.actividad(TipoActividad.PRODUCTO_AGREGADO, 10L, null));
        aplicar(Eventos.actividad(TipoActividad.PRODUCTO_AGREGADO, 20L, null));
        aplicar(Eventos.actividad(TipoActividad.PRODUCTO_QUITADO, 10L, null));
        aplicar(Eventos.actividad(TipoActividad.BUSQUEDA, null, "  Chorrillana "));
        aplicar(Eventos.actividad(TipoActividad.BUSQUEDA, null, "chorrillana"));

        TendenciasMenuConsulta.Tendencias tendencias = new TendenciasMenuConsulta(repositorio, calendario).ultimaHora();

        assertThat(tendencias.eventos()).isEqualTo(6);
        assertThat(tendencias.productosMasAgregados().get(0).etiqueta()).isEqualTo("Producto 10");
        assertThat(tendencias.productosMasAgregados().get(0).veces()).isEqualTo(2);
        assertThat(tendencias.productosMasAgregados().get(0).quitados()).isEqualTo(1);
        assertThat(tendencias.busquedas()).singleElement()
                .satisfies(b -> assertThat(b.etiqueta()).isEqualTo("chorrillana"))
                .satisfies(b -> assertThat(b.veces()).isEqualTo(2));
    }

    @Test
    void agregarAlCarritoSinProductoEsNoRecuperable() {
        EventoActividad sinProducto = Eventos.actividad(TipoActividad.PRODUCTO_AGREGADO, null, null);

        assertThatThrownBy(() -> manejador.procesar(sinProducto))
                .isInstanceOf(ErrorNoRecuperableException.class)
                .hasMessageContaining("productoId");
    }

    @Test
    void unEventoFechadoEnElFuturoEsNoRecuperable() {
        EventoActividad base = Eventos.actividad(TipoActividad.BUSQUEDA, null, "papas");
        EventoActividad delFuturo = new EventoActividad(1, base.idEvento(), base.tipo(), base.sesionId(), null,
                null, null, null, "papas", Instant.parse("2026-11-25T18:45:00Z"));

        assertThatThrownBy(() -> manejador.procesar(delFuturo)).isInstanceOf(ErrorNoRecuperableException.class);
    }

    private void aplicar(EventoActividad evento) {
        repositorio.aplicarUnaVez("pedido360-tendencias-menu", evento.idEvento(), manejador.procesar(evento));
    }
}
