package com.pedidos360.mensajeria.control;

import com.pedidos360.mensajeria.comun.ModoFalla;

/**
 * Estado de un consumidor, tal como lo reporta este microservicio al
 * administrador.
 *
 * @param id          identificador logico del consumidor
 * @param cola        cola que atiende
 * @param activo      true si esta leyendo mensajes
 * @param modoFalla   falla inducida vigente, para las demostraciones
 */
public record EstadoConsumidor(String id, String cola, boolean activo, ModoFalla modoFalla) {
}
