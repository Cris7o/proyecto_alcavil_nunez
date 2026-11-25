package com.pedidos360.streaming.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.pedidos360.streaming.Eventos;
import com.pedidos360.streaming.RepositorioEnMemoria;
import com.pedidos360.streaming.comun.CalendarioNegocio;
import com.pedidos360.streaming.comun.ErrorNoRecuperableException;
import com.pedidos360.streaming.eventos.EventoPedidoStream;
import com.pedidos360.streaming.eventos.TipoEventoPedido;

/** El dominio de ventas se prueba sin Kinesis: evento de entrada, tablero de salida. */
class TableroVentasTest {

    private final CalendarioNegocio calendario = new CalendarioNegocio(ZoneId.of("America/Santiago"),
            Clock.fixed(Instant.parse("2026-11-24T19:00:00Z"), ZoneOffset.UTC));
    private final TableroVentasManejador manejador = new TableroVentasManejador(calendario);
    private final RepositorioEnMemoria repositorio = new RepositorioEnMemoria();
    private final TableroVentasConsulta consulta = new TableroVentasConsulta(repositorio, calendario);

    @Test
    void elTableroSumaPedidosEntregasYProductos() {
        aplicar(Eventos.pedidoCreado(1));
        aplicar(Eventos.pedidoCreado(2));
        aplicar(Eventos.transicion(1, TipoEventoPedido.PEDIDO_ENTREGADO, "EN_REPARTO", Eventos.MEDIA_TARDE));

        TableroVentasConsulta.TableroVentas tablero = consulta.delDia(null);

        assertThat(tablero.dia()).isEqualTo("2026-11-24");
        assertThat(tablero.pedidosCreados()).isEqualTo(2);
        assertThat(tablero.montoCreado()).isEqualByComparingTo("28940");
        assertThat(tablero.pedidosEntregados()).isEqualTo(1);
        assertThat(tablero.ticketPromedio()).isEqualByComparingTo("14470");
        assertThat(tablero.porHora()).singleElement().satisfies(h -> assertThat(h.hora()).isEqualTo("15"));
        assertThat(tablero.topProductos().get(0).nombre()).isEqualTo("Hamburguesa Clasica");
        assertThat(tablero.topProductos().get(0).unidades()).isEqualTo(4);
    }

    @Test
    void unTipoQueNoCorrespondeAlEstadoEsNoRecuperable() {
        EventoPedidoStream base = Eventos.transicion(1, TipoEventoPedido.PEDIDO_LISTO, "EN_PREPARACION",
                Eventos.MEDIA_TARDE);
        EventoPedidoStream incoherente = new EventoPedidoStream(1, base.idEvento(), base.tipo(), 1L,
                base.estadoAnterior(), "ENTREGADO", 7L, BigDecimal.TEN, 1, base.items(), base.ocurridoEn(), "test");

        assertThatThrownBy(() -> manejador.procesar(incoherente))
                .isInstanceOf(ErrorNoRecuperableException.class)
                .hasMessageContaining("PEDIDO_LISTO");
    }

    private void aplicar(EventoPedidoStream evento) {
        repositorio.aplicarUnaVez("pedido360-tablero-ventas", evento.idEvento(), manejador.procesar(evento));
    }
}
