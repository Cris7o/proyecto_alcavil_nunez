package com.pedidos360.mensajeria.control;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.mensajeria.auditoria.AuditoriaService;
import com.pedidos360.mensajeria.comun.Actividad;
import com.pedidos360.mensajeria.comun.ModoFalla;
import com.pedidos360.mensajeria.comun.RegistroActividad;
import com.pedidos360.mensajeria.comun.RegistroEventosProcesados;

/**
 * Endpoints internos de control de esta instancia.
 *
 * <p>No son la API publica del sistema: el microservicio administrador es el que
 * expone la operacion al usuario y reenvia la orden aqui, porque los
 * contenedores de los consumidores viven en este proceso y no en el suyo.
 *
 * <p>Se montan bajo {@code /interno} para dejar claro que son un canal de
 * control entre servicios y no una API de negocio.
 */
@RestController
@RequestMapping("/interno/consumidores")
public class ControlConsumidoresController {

    private final ControlConsumidoresService controlConsumidores;
    private final AuditoriaService auditoriaService;
    private final RegistroEventosProcesados registroProcesados;
    private final RegistroActividad registroActividad;

    public ControlConsumidoresController(ControlConsumidoresService controlConsumidores,
            AuditoriaService auditoriaService, RegistroEventosProcesados registroProcesados,
            RegistroActividad registroActividad) {
        this.controlConsumidores = controlConsumidores;
        this.auditoriaService = auditoriaService;
        this.registroProcesados = registroProcesados;
        this.registroActividad = registroActividad;
    }

    @GetMapping
    public List<EstadoConsumidor> listar() {
        return controlConsumidores.listar();
    }

    @PostMapping("/{id}/pausar")
    public EstadoConsumidor pausar(@PathVariable String id) {
        return controlConsumidores.pausar(id);
    }

    @PostMapping("/{id}/reanudar")
    public EstadoConsumidor reanudar(@PathVariable String id) {
        return controlConsumidores.reanudar(id);
    }

    /** Induce una falla para demostrar las rutas de reintento y de DLQ. */
    @PostMapping("/{id}/falla-inducida")
    public EstadoConsumidor inducirFalla(@PathVariable String id, @RequestParam ModoFalla modo) {
        return controlConsumidores.establecerModoFalla(id, modo);
    }

    /**
     * Lo que esta instancia hizo con los ultimos mensajes: procesados,
     * duplicados descartados, reintentos y derivaciones a la DLQ. Es lo que
     * alimenta el panel de mensajeria del frontend.
     */
    @GetMapping("/actividad")
    public List<Actividad> actividad() {
        return registroActividad.ultimas();
    }

    /** Bitacora de auditoria de esta instancia, util para la demostracion. */
    @GetMapping("/auditoria/bitacora")
    public List<String> bitacora() {
        return auditoriaService.ultimasEntradas();
    }

    /** Cuantos ids de evento recuerda esta instancia para descartar duplicados. */
    @GetMapping("/idempotencia")
    public int eventosRecordados() {
        return registroProcesados.cantidadRecordada();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail noEncontrado(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
