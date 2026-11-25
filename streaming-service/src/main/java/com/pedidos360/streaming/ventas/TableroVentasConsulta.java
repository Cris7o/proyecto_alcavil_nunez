package com.pedidos360.streaming.ventas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pedidos360.streaming.comun.CalendarioNegocio;
import com.pedidos360.streaming.comun.RepositorioEstado;

/**
 * Lectura del tablero de ventas. Cualquier instancia puede responder, porque el
 * estado esta en la tabla compartida y no en memoria.
 */
@Service
public class TableroVentasConsulta {

    private static final int TOP_PRODUCTOS = 5;

    private final RepositorioEstado repositorio;
    private final CalendarioNegocio calendario;

    public TableroVentasConsulta(RepositorioEstado repositorio, CalendarioNegocio calendario) {
        this.repositorio = repositorio;
        this.calendario = calendario;
    }

    public TableroVentas delDia(String dia) {
        String diaConsultado = dia == null || dia.isBlank() ? calendario.dia(calendario.ahora()) : dia;
        List<Map<String, Object>> items = repositorio.leerParticion(TableroVentasManejador.PREFIJO_DIA + diaConsultado);

        Map<String, Object> resumen = Map.of();
        List<VentaHora> porHora = new ArrayList<>();
        List<VentaProducto> productos = new ArrayList<>();

        for (Map<String, Object> item : items) {
            String sk = (String) item.get("sk");
            if (TableroVentasManejador.SK_RESUMEN.equals(sk)) {
                resumen = item;
            } else if (sk.startsWith(TableroVentasManejador.PREFIJO_HORA)) {
                porHora.add(new VentaHora(sk.substring(TableroVentasManejador.PREFIJO_HORA.length()),
                        entero(item, "pedidos"), decimal(item, "monto")));
            } else if (sk.startsWith(TableroVentasManejador.PREFIJO_PRODUCTO)) {
                productos.add(new VentaProducto(entero(item, "productoId"), (String) item.get("nombre"),
                        entero(item, "unidades"), decimal(item, "monto")));
            }
        }

        porHora.sort(Comparator.comparing(VentaHora::hora));
        productos.sort(Comparator.comparing(VentaProducto::unidades).reversed());

        long creados = entero(resumen, "pedidosCreados");
        BigDecimal montoCreado = decimal(resumen, "montoCreado");
        BigDecimal ticketPromedio = creados == 0
                ? BigDecimal.ZERO
                : montoCreado.divide(BigDecimal.valueOf(creados), 0, RoundingMode.HALF_UP);

        return new TableroVentas(
                diaConsultado,
                creados,
                montoCreado,
                entero(resumen, "pedidosEntregados"),
                decimal(resumen, "montoEntregado"),
                entero(resumen, "pedidosCancelados"),
                decimal(resumen, "montoCancelado"),
                ticketPromedio,
                entero(resumen, "eventos"),
                (String) resumen.get("actualizadoEn"),
                porHora,
                productos.subList(0, Math.min(TOP_PRODUCTOS, productos.size())));
    }

    private static long entero(Map<String, Object> item, String atributo) {
        Object valor = item.get(atributo);
        return valor instanceof Number numero ? numero.longValue() : 0L;
    }

    private static BigDecimal decimal(Map<String, Object> item, String atributo) {
        Object valor = item.get(atributo);
        return valor instanceof BigDecimal numero ? numero : BigDecimal.ZERO;
    }

    public record TableroVentas(
            String dia,
            long pedidosCreados,
            BigDecimal montoCreado,
            long pedidosEntregados,
            BigDecimal montoEntregado,
            long pedidosCancelados,
            BigDecimal montoCancelado,
            BigDecimal ticketPromedio,
            long eventosProcesados,
            String ultimoEventoEn,
            List<VentaHora> porHora,
            List<VentaProducto> topProductos) {
    }

    public record VentaHora(String hora, long pedidos, BigDecimal monto) {
    }

    public record VentaProducto(long productoId, String nombre, long unidades, BigDecimal monto) {
    }
}
