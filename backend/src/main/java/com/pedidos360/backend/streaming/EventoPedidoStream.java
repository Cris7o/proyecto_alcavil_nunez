package com.pedidos360.backend.streaming;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.pedidos360.backend.messaging.TipoEvento;

/**
 * Evento del stream {@code pedido360-pedidos}: un cambio en el ciclo de vida de
 * un pedido.
 *
 * <p>Campos y para que sirve cada uno:
 * <ul>
 * <li>{@code version}: version del contrato, para evolucionarlo sin romper a los
 * consumidores.
 * <li>{@code idEvento}: UUID unico. Es la clave de idempotencia de los
 * consumidores (Kinesis puede entregar un registro mas de una vez) y el
 * identificador de correlacion en todos los logs.
 * <li>{@code tipo}, {@code estadoAnterior}, {@code estadoNuevo}: la transicion.
 * El estado anterior permite medir cuanto duro cada etapa.
 * <li>{@code pedidoId}: tambien es la <b>clave de particion</b>. Todos los
 * eventos de un pedido van al mismo shard y se leen en orden, y como hay
 * muchos pedidos distintos la carga se reparte pareja entre los shards.
 * <li>{@code clienteId}, {@code total}, {@code cantidadItems}, {@code items}:
 * lo que necesita la analitica de ventas sin consultar la base de datos.
 * <li>{@code ocurridoEn}: cuando paso, no cuando se leyo.
 * <li>{@code origen}: quien lo genero (el servicio o una prueba).
 * </ul>
 *
 * <p>A diferencia del mensaje de SNS, no lleva nombre ni correo del cliente: el
 * stream se retiene 7 dias y lo leen varias aplicaciones, asi que se minimizan
 * los datos personales que viajan por el.
 */
public record EventoPedidoStream(
        int version,
        String idEvento,
        TipoEvento tipo,
        Long pedidoId,
        String estadoAnterior,
        String estadoNuevo,
        Long clienteId,
        BigDecimal total,
        int cantidadItems,
        List<Item> items,
        Instant ocurridoEn,
        String origen) {

    public static final int VERSION_ACTUAL = 1;
    public static final String ORIGEN = "pedidos-service";

    /** Clave de particion: el id del pedido. */
    public String claveParticion() {
        return String.valueOf(pedidoId);
    }

    public record Item(Long productoId, String productoNombre, int cantidad, BigDecimal precioUnitario) {
    }
}
