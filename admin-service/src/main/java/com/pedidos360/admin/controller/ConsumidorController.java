package com.pedidos360.admin.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.admin.service.ConsumidorAdminService;

/**
 * API REST de consumidores.
 *
 * <pre>
 * GET  /admin/consumidores                        estado de cada instancia
 * POST /admin/consumidores/{id}/pausar            deja de leer la cola
 * POST /admin/consumidores/{id}/reanudar          vuelve a leer la cola
 * POST /admin/consumidores/{id}/falla-inducida    provoca una falla, para demostrar
 * </pre>
 *
 * <p>Pausar no pierde mensajes: un mensaje solo sale de la cola cuando alguien
 * lo confirma, y un consumidor pausado no confirma nada. Se van acumulando y al
 * reanudar se procesan todos.
 */
@RestController
@RequestMapping("/admin/consumidores")
public class ConsumidorController {

    private final ConsumidorAdminService consumidorAdminService;

    public ConsumidorController(ConsumidorAdminService consumidorAdminService) {
        this.consumidorAdminService = consumidorAdminService;
    }

    @GetMapping
    public List<Map<String, Object>> estado() {
        return consumidorAdminService.estado();
    }

    /**
     * Actividad reciente de todas las instancias, en una sola lista. Es lo que
     * el panel del frontend muestra en vivo, y reemplaza tener que leer la
     * consola de cada consumidor.
     */
    @GetMapping("/actividad")
    public List<Map<String, Object>> actividad() {
        return consumidorAdminService.actividad();
    }

    @PostMapping("/{id}/pausar")
    public List<Map<String, Object>> pausar(@PathVariable String id) {
        return consumidorAdminService.pausar(id);
    }

    @PostMapping("/{id}/reanudar")
    public List<Map<String, Object>> reanudar(@PathVariable String id) {
        return consumidorAdminService.reanudar(id);
    }

    /**
     * Induce una falla en el consumidor para demostrar el manejo de errores.
     *
     * @param modo {@code RECUPERABLE} reintenta hasta agotarse y recien
     *             entonces deriva a la DLQ; {@code NO_RECUPERABLE} deriva en el
     *             primer intento; {@code NINGUNA} vuelve al comportamiento normal
     */
    @PostMapping("/{id}/falla-inducida")
    public List<Map<String, Object>> inducirFalla(@PathVariable String id,
            @RequestParam String modo) {
        return consumidorAdminService.inducirFalla(id, modo);
    }
}
