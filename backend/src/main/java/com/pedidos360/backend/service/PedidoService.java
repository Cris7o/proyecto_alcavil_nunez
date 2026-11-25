package com.pedidos360.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pedidos360.backend.dto.ItemPedidoResponse;
import com.pedidos360.backend.dto.PedidoRequest;
import com.pedidos360.backend.dto.PedidoResponse;
import com.pedidos360.backend.exception.RecursoNoEncontradoException;
import com.pedidos360.backend.exception.RolNoAutorizadoException;
import com.pedidos360.backend.messaging.EventoPedido;
import com.pedidos360.backend.messaging.PublicadorEventos;
import com.pedidos360.backend.messaging.TipoEvento;
import com.pedidos360.backend.model.EstadoPedido;
import com.pedidos360.backend.model.ItemPedido;
import com.pedidos360.backend.model.Pedido;
import com.pedidos360.backend.model.Producto;
import com.pedidos360.backend.model.RolUsuario;
import com.pedidos360.backend.model.Usuario;
import com.pedidos360.backend.repository.PedidoRepository;
import com.pedidos360.backend.streaming.EventoPedidoStream;
import com.pedidos360.backend.streaming.PublicadorStreamPedidos;

@Service
public class PedidoService {

    private final PedidoRepository pedidoRepository;
    private final ProductoService productoService;
    private final PedidoEstadoService pedidoEstadoService;
    private final PublicadorEventos publicadorEventos;
    private final PublicadorStreamPedidos publicadorStream;

    public PedidoService(PedidoRepository pedidoRepository, ProductoService productoService,
            PedidoEstadoService pedidoEstadoService, PublicadorEventos publicadorEventos,
            PublicadorStreamPedidos publicadorStream) {
        this.pedidoRepository = pedidoRepository;
        this.productoService = productoService;
        this.pedidoEstadoService = pedidoEstadoService;
        this.publicadorEventos = publicadorEventos;
        this.publicadorStream = publicadorStream;
    }

    public PedidoResponse crear(PedidoRequest request, Usuario cliente) {
        Pedido pedido = new Pedido();
        pedido.setCliente(cliente);
        pedido.setEstado(EstadoPedido.PENDIENTE);

        request.getItems().forEach(itemRequest -> {
            Producto producto = productoService.obtenerEntidad(itemRequest.getProductoId());
            if (!producto.isDisponible()) {
                throw new RecursoNoEncontradoException("Producto no disponible: " + producto.getId());
            }

            ItemPedido item = new ItemPedido();
            item.setPedido(pedido);
            item.setProducto(producto);
            item.setCantidad(itemRequest.getCantidad());
            item.setPrecioUnitario(producto.getPrecio());
            pedido.getItems().add(item);
        });

        PedidoResponse respuesta = aResponse(pedidoRepository.save(pedido));
        publicarEvento(TipoEvento.PEDIDO_CREADO, respuesta, cliente, null);
        return respuesta;
    }

    public List<PedidoResponse> listarPorCliente(Usuario cliente) {
        return pedidoRepository.findByCliente(cliente).stream().map(this::aResponse).toList();
    }

    public List<PedidoResponse> listarTodos() {
        return pedidoRepository.findAll().stream().map(this::aResponse).toList();
    }

    public List<PedidoResponse> listarPorEstados(EstadoPedido... estados) {
        List<EstadoPedido> permitidos = Arrays.asList(estados);
        return pedidoRepository.findAll().stream()
                .filter(pedido -> permitidos.contains(pedido.getEstado()))
                .map(this::aResponse)
                .toList();
    }

    public PedidoResponse cambiarEstado(Long pedidoId, EstadoPedido nuevoEstado, Usuario solicitante) {
        Pedido pedido = obtenerEntidad(pedidoId);

        if (nuevoEstado == EstadoPedido.CANCELADO
                && solicitante.getRol() == RolUsuario.CLIENTE
                && !pedido.getCliente().getId().equals(solicitante.getId())) {
            throw new RolNoAutorizadoException("Un cliente solo puede cancelar sus propios pedidos");
        }

        EstadoPedido estadoAnterior = pedido.getEstado();
        pedidoEstadoService.aplicarTransicion(pedido, nuevoEstado, solicitante.getRol());

        PedidoResponse respuesta = aResponse(pedidoRepository.save(pedido));
        publicarEvento(TipoEvento.desdeEstado(nuevoEstado), respuesta, pedido.getCliente(), estadoAnterior);
        return respuesta;
    }

    /**
     * Arma el evento y lo entrega al publicador. Vive en una sola linea del
     * flujo de negocio para que agregar o quitar mensajeria no implique tocar
     * las reglas del pedido.
     */
    private void publicarEvento(TipoEvento tipo, PedidoResponse pedido, Usuario cliente,
            EstadoPedido estadoAnterior) {
        // El mismo idEvento viaja por SNS y por Kinesis: permite correlacionar
        // en los logs lo que hicieron las colas y lo que hicieron los streams.
        String idEvento = UUID.randomUUID().toString();
        Instant ocurridoEn = Instant.now();

        // Mensajeria del Parcial 2 (SNS -> SQS), sin cambios.
        publicadorEventos.publicar(new EventoPedido(
                idEvento,
                tipo,
                pedido.id(),
                estadoAnterior == null ? null : estadoAnterior.name(),
                pedido.estado().name(),
                cliente.getId(),
                cliente.getNombre(),
                cliente.getEmail(),
                pedido.total(),
                ocurridoEn));

        // Streaming (Parcial 3): el mismo hecho, en el stream de pedidos.
        publicadorStream.publicar(new EventoPedidoStream(
                EventoPedidoStream.VERSION_ACTUAL,
                idEvento,
                tipo,
                pedido.id(),
                estadoAnterior == null ? null : estadoAnterior.name(),
                pedido.estado().name(),
                cliente.getId(),
                pedido.total(),
                pedido.items().stream().mapToInt(ItemPedidoResponse::cantidad).sum(),
                pedido.items().stream()
                        .map(item -> new EventoPedidoStream.Item(item.productoId(), item.nombreProducto(),
                                item.cantidad(), item.precioUnitario()))
                        .toList(),
                ocurridoEn,
                EventoPedidoStream.ORIGEN));
    }

    private Pedido obtenerEntidad(Long id) {
        return pedidoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pedido no encontrado: " + id));
    }

    private PedidoResponse aResponse(Pedido pedido) {
        List<ItemPedidoResponse> items = pedido.getItems().stream()
                .map(item -> new ItemPedidoResponse(
                        item.getProducto().getId(),
                        item.getProducto().getNombre(),
                        item.getCantidad(),
                        item.getPrecioUnitario(),
                        item.getPrecioUnitario().multiply(BigDecimal.valueOf(item.getCantidad()))))
                .toList();

        BigDecimal total = items.stream()
                .map(ItemPedidoResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PedidoResponse(
                pedido.getId(),
                pedido.getCliente().getId(),
                pedido.getEstado(),
                pedido.getFechaCreacion(),
                items,
                total);
    }
}
