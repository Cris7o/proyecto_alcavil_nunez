package com.pedidos360.admin.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.admin.dto.StreamDto;
import com.pedidos360.admin.service.StreamAdminService;
import com.pedidos360.admin.service.StreamConsumidoresService;

import jakarta.validation.constraints.NotBlank;

/**
 * API REST de aplicaciones consumidoras (consumer groups de Kinesis).
 *
 * <pre>
 * GET /admin/aplicaciones                     las aplicaciones y sus tablas de coordinacion
 * GET /admin/aplicaciones/{nombre}            leases: shard, instancia duenia, checkpoint y retraso
 * GET /admin/aplicaciones/instancias          lo que procesa cada instancia de streaming-service
 * PUT /admin/aplicaciones/{nombre}/falla      induce una falla en todas las instancias (demostracion)
 * </pre>
 */
@RestController
@RequestMapping("/admin/aplicaciones")
public class AplicacionConsumidoraController {

    private final StreamAdminService streamAdmin;
    private final StreamConsumidoresService consumidores;

    public AplicacionConsumidoraController(StreamAdminService streamAdmin, StreamConsumidoresService consumidores) {
        this.streamAdmin = streamAdmin;
        this.consumidores = consumidores;
    }

    @GetMapping
    public List<StreamDto.Aplicacion> listar() {
        return streamAdmin.listarAplicaciones();
    }

    @GetMapping("/instancias")
    public List<Map<String, Object>> instancias() {
        return consumidores.estadoInstancias();
    }

    /** Con {@code conRetraso=true} lee el stream desde cada checkpoint para medir el retraso por shard. */
    @GetMapping("/{nombre}")
    public StreamDto.Aplicacion inspeccionar(@PathVariable String nombre,
            @RequestParam(defaultValue = "true") boolean conRetraso) {
        return streamAdmin.inspeccionarAplicacion(nombre, conRetraso);
    }

    @PutMapping("/{nombre}/falla")
    public List<Map<String, Object>> inducirFalla(@PathVariable String nombre, @RequestBody CambioFalla cambio) {
        return consumidores.inducirFalla(nombre, cambio.modo());
    }

    public record CambioFalla(@NotBlank String modo) {
    }
}
