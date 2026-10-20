package com.pedidos360.backend.service;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.pedidos360.backend.exception.RolNoAutorizadoException;
import com.pedidos360.backend.exception.TransicionInvalidaException;
import com.pedidos360.backend.model.EstadoPedido;
import com.pedidos360.backend.model.Pedido;
import com.pedidos360.backend.model.RolUsuario;

@Service
public class PedidoEstadoService {

    private static final Map<EstadoPedido, Map<EstadoPedido, Set<RolUsuario>>> TRANSICIONES = Map.of(
            EstadoPedido.PENDIENTE, Map.of(
                    EstadoPedido.EN_PREPARACION, Set.of(RolUsuario.COCINA),
                    EstadoPedido.CANCELADO, Set.of(RolUsuario.CLIENTE, RolUsuario.ADMIN)),
            EstadoPedido.EN_PREPARACION, Map.of(
                    EstadoPedido.LISTO, Set.of(RolUsuario.COCINA)),
            EstadoPedido.LISTO, Map.of(
                    EstadoPedido.EN_REPARTO, Set.of(RolUsuario.REPARTIDOR)),
            EstadoPedido.EN_REPARTO, Map.of(
                    EstadoPedido.ENTREGADO, Set.of(RolUsuario.REPARTIDOR)));

    public void aplicarTransicion(Pedido pedido, EstadoPedido destino, RolUsuario rolSolicitante) {
        EstadoPedido actual = pedido.getEstado();
        Map<EstadoPedido, Set<RolUsuario>> destinosPermitidos = TRANSICIONES.get(actual);

        if (destinosPermitidos == null || !destinosPermitidos.containsKey(destino)) {
            throw new TransicionInvalidaException(
                    "No se puede pasar de %s a %s".formatted(actual, destino));
        }

        Set<RolUsuario> rolesPermitidos = destinosPermitidos.get(destino);
        if (!rolesPermitidos.contains(rolSolicitante)) {
            throw new RolNoAutorizadoException(
                    "El rol %s no puede aplicar la transicion %s -> %s".formatted(rolSolicitante, actual, destino));
        }

        pedido.setEstado(destino);
    }
}
