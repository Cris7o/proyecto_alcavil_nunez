package com.pedidos360.streaming.control;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.streaming.comun.ModoFalla;
import com.pedidos360.streaming.comun.SimuladorFallas;
import com.pedidos360.streaming.config.StreamingProperties;
import com.pedidos360.streaming.kcl.AplicacionesConsumidoras;
import com.pedidos360.streaming.kcl.EstadisticasConsumo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Canal de control de esta instancia. Lo usa el microservicio administrador.
 *
 * <pre>
 * GET /control/estado                   que shards procesa esta instancia, por aplicacion
 * GET /control/fallas                   fallas inducidas activas
 * PUT /control/fallas/{aplicacion}      induce o quita una falla (demostracion)
 * </pre>
 */
@RestController
@RequestMapping("/control")
public class ControlController {

    private final AplicacionesConsumidoras aplicaciones;
    private final EstadisticasConsumo estadisticas;
    private final SimuladorFallas simuladorFallas;
    private final StreamingProperties propiedades;

    public ControlController(AplicacionesConsumidoras aplicaciones, EstadisticasConsumo estadisticas,
            SimuladorFallas simuladorFallas, StreamingProperties propiedades) {
        this.aplicaciones = aplicaciones;
        this.estadisticas = estadisticas;
        this.simuladorFallas = simuladorFallas;
        this.propiedades = propiedades;
    }

    @GetMapping("/estado")
    public EstadoInstancia estado() {
        List<EstadoAplicacion> porAplicacion = propiedades.getAplicaciones().stream()
                .map(aplicacion -> new EstadoAplicacion(
                        aplicacion.getNombre(),
                        propiedades.nombreStream(aplicacion.getStream()),
                        aplicacion.getDominio(),
                        aplicacion.isActiva(),
                        simuladorFallas.modoDe(aplicacion.getNombre()),
                        estadisticas.vista(aplicacion.getNombre())))
                .toList();
        return new EstadoInstancia(aplicaciones.instancia(), aplicaciones.workerId(), aplicaciones.isRunning(),
                porAplicacion);
    }

    @GetMapping("/fallas")
    public Map<String, ModoFalla> fallas() {
        return simuladorFallas.estado();
    }

    @PutMapping("/fallas/{aplicacion}")
    public Map<String, ModoFalla> inducirFalla(@PathVariable String aplicacion,
            @Valid @RequestBody CambioFalla cambio) {
        // Valida que la aplicacion exista: lanza NoSuchElementException (404) si no.
        propiedades.aplicacion(aplicacion);
        simuladorFallas.establecer(aplicacion, cambio.modo());
        return simuladorFallas.estado();
    }

    public record CambioFalla(@NotNull ModoFalla modo) {
    }

    public record EstadoInstancia(String instancia, String workerId, boolean consumiendo,
            List<EstadoAplicacion> aplicaciones) {
    }

    public record EstadoAplicacion(String nombre, String stream, String dominio, boolean activaEnEstaInstancia,
            ModoFalla fallaInducida, List<EstadisticasConsumo.VistaShard> shards) {
    }
}
