package com.pedidos360.streaming.ventas;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.pedidos360.streaming.comun.CalendarioNegocio;
import com.pedidos360.streaming.comun.CambioEstado;
import com.pedidos360.streaming.comun.ErrorNoRecuperableException;
import com.pedidos360.streaming.comun.ManejadorDominio;
import com.pedidos360.streaming.eventos.EventoPedidoStream;
import com.pedidos360.streaming.eventos.ItemEventoPedido;
import com.pedidos360.streaming.eventos.TipoEventoPedido;

/**
 * Dominio de ventas: alimenta el tablero de ventas en linea.
 *
 * <p>Aplicacion consumidora {@code pedido360-tablero-ventas}, sobre el stream de
 * pedidos. Por cada evento suma al resumen del dia, a la serie por hora y al
 * ranking de productos vendidos.
 *
 * <p>No conoce Kinesis ni DynamoDB: solo traduce un evento en cambios de estado.
 */
@Component
public class TableroVentasManejador implements ManejadorDominio<EventoPedidoStream> {

    public static final String DOMINIO = "ventas";

    /** El tablero guarda 30 dias de historia; despues el TTL borra los items. */
    static final long HORAS_DE_VIDA = 30L * 24;

    static final String PREFIJO_DIA = "VENTAS#";
    static final String SK_RESUMEN = "RESUMEN";
    static final String PREFIJO_HORA = "HORA#";
    static final String PREFIJO_PRODUCTO = "PRODUCTO#";

    private final CalendarioNegocio calendario;

    public TableroVentasManejador(CalendarioNegocio calendario) {
        this.calendario = calendario;
    }

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
        validarCoherencia(evento);

        String pk = PREFIJO_DIA + calendario.dia(evento.ocurridoEn());
        List<CambioEstado> cambios = new ArrayList<>();

        CambioEstado.Constructor resumen = CambioEstado.en(pk, SK_RESUMEN)
                .sumar("eventos", 1)
                .fijar("actualizadoEn", evento.ocurridoEn().toString());

        switch (evento.tipo()) {
            case PEDIDO_CREADO -> {
                resumen.sumar("pedidosCreados", 1).sumar("montoCreado", evento.total());
                cambios.add(CambioEstado.en(pk, PREFIJO_HORA + calendario.hora(evento.ocurridoEn()))
                        .sumar("pedidos", 1)
                        .sumar("monto", evento.total())
                        .build(HORAS_DE_VIDA));
                cambios.addAll(ventasPorProducto(pk, evento.items()));
            }
            case PEDIDO_ENTREGADO -> resumen.sumar("pedidosEntregados", 1).sumar("montoEntregado", evento.total());
            case PEDIDO_CANCELADO -> resumen.sumar("pedidosCancelados", 1).sumar("montoCancelado", evento.total());
            default -> resumen.sumar("transiciones" + evento.tipo().name(), 1);
        }

        cambios.add(0, resumen.build(HORAS_DE_VIDA));
        return cambios;
    }

    /**
     * Reglas de negocio que el DTO no puede expresar con anotaciones. Si no se
     * cumplen, el evento esta mal construido y reintentar no lo arregla.
     */
    private void validarCoherencia(EventoPedidoStream evento) {
        TipoEventoPedido tipo = evento.tipo();
        if (!tipo.estadoResultante().equals(evento.estadoNuevo())) {
            throw new ErrorNoRecuperableException("El tipo " + tipo + " deberia dejar el pedido en "
                    + tipo.estadoResultante() + ", pero estadoNuevo=" + evento.estadoNuevo());
        }
        if (tipo == TipoEventoPedido.PEDIDO_CREADO) {
            if (evento.items() == null || evento.items().isEmpty()) {
                throw new ErrorNoRecuperableException("Un pedido creado debe traer sus items");
            }
            BigDecimal suma = evento.items().stream()
                    .map(item -> item.precioUnitario().multiply(BigDecimal.valueOf(item.cantidad())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (suma.compareTo(evento.total()) != 0) {
                throw new ErrorNoRecuperableException(
                        "El total del pedido (" + evento.total() + ") no cuadra con sus items (" + suma + ")");
            }
        }
    }

    /** Un cambio por producto. Si el mismo producto aparece dos veces, se agrupa. */
    private List<CambioEstado> ventasPorProducto(String pk, List<ItemEventoPedido> items) {
        Map<Long, CambioEstado.Constructor> porProducto = new LinkedHashMap<>();
        for (ItemEventoPedido item : items) {
            porProducto.computeIfAbsent(item.productoId(),
                    id -> CambioEstado.en(pk, PREFIJO_PRODUCTO + id)
                            .fijar("productoId", id)
                            .fijar("nombre", item.productoNombre()))
                    .sumar("unidades", item.cantidad())
                    .sumar("monto", item.precioUnitario().multiply(BigDecimal.valueOf(item.cantidad())));
        }
        return porProducto.values().stream().map(constructor -> constructor.build(HORAS_DE_VIDA)).toList();
    }
}
