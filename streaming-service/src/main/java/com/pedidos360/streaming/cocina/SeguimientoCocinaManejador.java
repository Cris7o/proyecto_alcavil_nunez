package com.pedidos360.streaming.cocina;

import java.util.List;

import org.springframework.stereotype.Component;

import com.pedidos360.streaming.comun.CambioEstado;
import com.pedidos360.streaming.comun.ErrorNoRecuperableException;
import com.pedidos360.streaming.comun.ManejadorDominio;
import com.pedidos360.streaming.eventos.EventoPedidoStream;
import com.pedidos360.streaming.eventos.TipoEventoPedido;

/**
 * Dominio de cocina: sigue el recorrido de cada pedido por las etapas y mide
 * cuanto tarda en cada una.
 *
 * <p>Aplicacion consumidora {@code pedido360-seguimiento-cocina}. Lee el mismo
 * stream que el tablero de ventas, pero con su propia tabla de coordinacion y
 * su propio checkpoint: si este dominio se atrasa o se detiene, el tablero de
 * ventas sigue al dia, y viceversa.
 *
 * <p>Se apoya en el orden por clave de particion: como la clave es
 * {@code pedidoId}, los eventos de un pedido llegan en el orden en que
 * ocurrieron y el ultimo estado escrito es el vigente.
 */
@Component
public class SeguimientoCocinaManejador implements ManejadorDominio<EventoPedidoStream> {

    public static final String DOMINIO = "cocina";

    /** Un pedido se sigue por dos dias; despues el TTL lo borra. */
    static final long HORAS_DE_VIDA = 48;

    static final String PK = "SEGUIMIENTO";
    static final String PREFIJO_PEDIDO = "PEDIDO#";
    static final String PREFIJO_INSTANTE = "en_";

    @Override
    public String dominio() {
        return DOMINIO;
    }

    @Override
    public Class<EventoPedidoStream> tipoEvento() {
        return EventoPedidoStream.class;
    }

    @Override
    public String idEvento(EventoPedidoStream evento) {
        return evento.idEvento();
    }

    @Override
    public List<CambioEstado> procesar(EventoPedidoStream evento) {
        if (evento.tipo() != TipoEventoPedido.PEDIDO_CREADO && evento.estadoAnterior() == null) {
            throw new ErrorNoRecuperableException(
                    "El evento " + evento.tipo() + " del pedido " + evento.pedidoId()
                            + " no indica de que estado viene: no se puede medir la etapa");
        }
        if (!evento.tipo().estadoResultante().equals(evento.estadoNuevo())) {
            throw new ErrorNoRecuperableException("El tipo " + evento.tipo() + " no corresponde al estado "
                    + evento.estadoNuevo());
        }

        String instante = evento.ocurridoEn().toString();

        return List.of(CambioEstado.en(PK, skDe(evento.pedidoId()))
                .fijar("pedidoId", evento.pedidoId())
                .fijar("estado", evento.estadoNuevo())
                .fijar("actualizadoEn", instante)
                .fijar("clienteId", evento.clienteId())
                .fijar("total", evento.total())
                .fijar("cantidadItems", evento.cantidadItems())
                // La primera vez que llego a cada estado. Si el evento se
                // reprocesa, no se pisa el instante original.
                .fijarSiNoExiste(PREFIJO_INSTANTE + evento.estadoNuevo(), instante)
                .build(HORAS_DE_VIDA));
    }

    /** Relleno con ceros para que el orden del sk sea el orden numerico del pedido. */
    static String skDe(Long pedidoId) {
        return PREFIJO_PEDIDO + String.format("%012d", pedidoId);
    }
}
