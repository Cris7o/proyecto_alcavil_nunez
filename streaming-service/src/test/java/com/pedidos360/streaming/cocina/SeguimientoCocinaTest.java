package com.pedidos360.streaming.cocina;

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
import com.pedidos360.streaming.config.StreamingProperties;
import com.pedidos360.streaming.config.StreamingProperties.EstadoPedidoClave;
import com.pedidos360.streaming.eventos.EventoPedidoStream;
import com.pedidos360.streaming.eventos.TipoEventoPedido;

class SeguimientoCocinaTest {

    private static final Instant T0 = Instant.parse("2026-11-24T18:00:00Z");

    private final SeguimientoCocinaManejador manejador = new SeguimientoCocinaManejador();
    private final RepositorioEnMemoria repositorio = new RepositorioEnMemoria();

    @Test
    void mideCadaEtapaYMarcaAtrasadoElPedidoQueSuperaElMaximo() {
        // Pedido 1: espera 4 min, se prepara en 12 min y queda LISTO.
        aplicar(creado(1, T0));
        aplicar(Eventos.transicion(1, TipoEventoPedido.PEDIDO_EN_PREPARACION, "PENDIENTE", T0.plusSeconds(240)));
        aplicar(Eventos.transicion(1, TipoEventoPedido.PEDIDO_LISTO, "EN_PREPARACION", T0.plusSeconds(960)));
        // Pedido 2: lleva 20 minutos en preparacion (maximo 15).
        aplicar(creado(2, T0));
        aplicar(Eventos.transicion(2, TipoEventoPedido.PEDIDO_EN_PREPARACION, "PENDIENTE", T0.plusSeconds(60)));

        SeguimientoCocinaConsulta.SeguimientoCocina seguimiento = consultaA(T0.plusSeconds(1260)).actual();

        assertThat(seguimiento.pedidosEnCurso()).isEqualTo(2);
        assertThat(seguimiento.pedidosAtrasados()).isEqualTo(1);
        assertThat(seguimiento.enCurso().get(0).pedidoId()).isEqualTo(2);
        assertThat(seguimiento.enCurso().get(0).atrasado()).isTrue();
        SeguimientoCocinaConsulta.PedidoEnSeguimiento listo = seguimiento.enCurso().get(1);
        assertThat(listo.minutosEspera()).isEqualTo(4);
        assertThat(listo.minutosPreparacion()).isEqualTo(12);
    }

    @Test
    void unaTransicionSinEstadoAnteriorEsNoRecuperable() {
        EventoPedidoStream sinOrigen = Eventos.transicion(1, TipoEventoPedido.PEDIDO_LISTO, null, T0);

        assertThatThrownBy(() -> manejador.procesar(sinOrigen))
                .isInstanceOf(ErrorNoRecuperableException.class)
                .hasMessageContaining("no indica de que estado viene");
    }

    private EventoPedidoStream creado(long pedidoId, Instant cuando) {
        EventoPedidoStream base = Eventos.pedidoCreado(pedidoId);
        return new EventoPedidoStream(1, base.idEvento(), base.tipo(), pedidoId, null, "PENDIENTE", 7L,
                base.total(), base.cantidadItems(), base.items(), cuando, "test");
    }

    private SeguimientoCocinaConsulta consultaA(Instant ahora) {
        StreamingProperties propiedades = new StreamingProperties();
        propiedades.getCocina().getMinutosMaximos().put(EstadoPedidoClave.PENDIENTE, 5);
        propiedades.getCocina().getMinutosMaximos().put(EstadoPedidoClave.EN_PREPARACION, 15);
        propiedades.getCocina().getMinutosMaximos().put(EstadoPedidoClave.LISTO, 10);
        CalendarioNegocio calendario = new CalendarioNegocio(ZoneId.of("America/Santiago"),
                Clock.fixed(ahora, ZoneOffset.UTC));
        return new SeguimientoCocinaConsulta(repositorio, calendario, propiedades);
    }

    private void aplicar(EventoPedidoStream evento) {
        repositorio.aplicarUnaVez("pedido360-seguimiento-cocina", evento.idEvento(), manejador.procesar(evento));
    }
}
