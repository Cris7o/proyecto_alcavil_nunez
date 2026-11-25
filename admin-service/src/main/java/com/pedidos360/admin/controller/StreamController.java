package com.pedidos360.admin.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.admin.config.StreamingProperties;
import com.pedidos360.admin.dto.StreamDto;
import com.pedidos360.admin.service.EventoPruebaService;
import com.pedidos360.admin.service.StreamAdminService;
import com.pedidos360.admin.service.StreamMonitoreoService;
import com.pedidos360.admin.service.StreamTopologiaService;

import jakarta.validation.Valid;

/**
 * API REST de administracion de streams.
 *
 * <pre>
 * GET    /admin/streams                       lista los streams del proyecto
 * POST   /admin/streams                       crea un stream (nombre, shards, retencion, modo)
 * GET    /admin/streams/{nombre}              describe el stream y sus shards
 * PUT    /admin/streams/{nombre}/shards       resharding uniforme
 * PUT    /admin/streams/{nombre}/retencion    cambia la retencion
 * DELETE /admin/streams/{nombre}              elimina el stream
 * GET    /admin/streams/{nombre}/registros    visor de datos: ultimos registros
 * GET    /admin/streams/{nombre}/metricas     series de CloudWatch del stream y por shard
 *
 * GET    /admin/streams/topologia             lo declarado en application.yml
 * POST   /admin/streams/topologia             crea o ajusta todo lo declarado (idempotente)
 * DELETE /admin/streams/topologia             elimina streams y alarma (?incluirTablas=true: tablas tambien)
 * GET    /admin/streams/alarma                estado de la alarma de retraso
 * POST   /admin/streams/alarma                crea o actualiza la alarma
 * POST   /admin/streams/pruebas               publica un evento de prueba en el stream de pedidos
 * </pre>
 *
 * <p>El controlador no toca el SDK: cada metodo delega en un servicio.
 */
@RestController
@RequestMapping("/admin/streams")
public class StreamController {

    private final StreamAdminService streamAdmin;
    private final StreamTopologiaService topologia;
    private final StreamMonitoreoService monitoreo;
    private final EventoPruebaService pruebas;
    private final StreamingProperties propiedades;

    public StreamController(StreamAdminService streamAdmin, StreamTopologiaService topologia,
            StreamMonitoreoService monitoreo, EventoPruebaService pruebas, StreamingProperties propiedades) {
        this.streamAdmin = streamAdmin;
        this.topologia = topologia;
        this.monitoreo = monitoreo;
        this.pruebas = pruebas;
        this.propiedades = propiedades;
    }

    @GetMapping
    public List<StreamDto.Resumen> listar() {
        return streamAdmin.listarStreams();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StreamDto.Operacion crear(@Valid @RequestBody StreamDto.CrearStream peticion) {
        return streamAdmin.crearStream(peticion);
    }

    @GetMapping("/topologia")
    public StreamingProperties topologiaDeclarada() {
        return propiedades;
    }

    @PostMapping("/topologia")
    public List<StreamDto.Operacion> declarar() {
        return topologia.declarar();
    }

    @DeleteMapping("/topologia")
    public List<StreamDto.Operacion> eliminarTopologia(@RequestParam(defaultValue = "false") boolean incluirTablas) {
        return topologia.eliminar(incluirTablas);
    }

    @GetMapping("/alarma")
    public StreamDto.Alarma alarma() {
        return monitoreo.describirAlarma();
    }

    @PostMapping("/alarma")
    public StreamDto.Alarma declararAlarma() {
        return monitoreo.declararAlarma();
    }

    @PostMapping("/pruebas")
    public StreamDto.ResultadoPrueba publicarPrueba(@Valid @RequestBody StreamDto.PublicarPrueba peticion) {
        return pruebas.publicar(peticion.variante());
    }

    @GetMapping("/{nombre}")
    public StreamDto.Detalle describir(@PathVariable String nombre) {
        return streamAdmin.describirStream(nombre);
    }

    @PutMapping("/{nombre}/shards")
    public StreamDto.Operacion cambiarShards(@PathVariable String nombre,
            @Valid @RequestBody StreamDto.CambioShards cambio) {
        return streamAdmin.cambiarShards(nombre, cambio.shards());
    }

    @PutMapping("/{nombre}/retencion")
    public StreamDto.Operacion cambiarRetencion(@PathVariable String nombre,
            @Valid @RequestBody StreamDto.CambioRetencion cambio) {
        return streamAdmin.cambiarRetencion(nombre, cambio.horas());
    }

    @DeleteMapping("/{nombre}")
    public StreamDto.Operacion eliminar(@PathVariable String nombre) {
        return streamAdmin.eliminarStream(nombre);
    }

    @GetMapping("/{nombre}/registros")
    public List<StreamDto.Registro> registros(@PathVariable String nombre,
            @RequestParam(defaultValue = "20") int limite) {
        return streamAdmin.leerUltimos(nombre, Math.min(Math.max(limite, 1), 200));
    }

    @GetMapping("/{nombre}/metricas")
    public StreamDto.Metricas metricas(@PathVariable String nombre, @RequestParam(defaultValue = "30") int minutos) {
        return monitoreo.metricas(nombre, Math.min(Math.max(minutos, 5), 180));
    }
}
