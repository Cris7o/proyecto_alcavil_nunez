package com.pedidos360.admin.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.admin.dto.ColaDto;
import com.pedidos360.admin.service.ColaAdminService;

import jakarta.validation.Valid;

/**
 * API REST de colas.
 *
 * <pre>
 * GET    /admin/colas                  lista las colas del proyecto
 * POST   /admin/colas                  crea una cola, con DLQ y Redrive Policy
 * GET    /admin/colas/estado           estado de las tres colas declaradas
 * GET    /admin/colas/{nombre}         estado de una cola y de su DLQ
 * DELETE /admin/colas/{nombre}         elimina una cola
 * POST   /admin/colas/{nombre}/purga   vacia una cola
 * </pre>
 *
 * <p>El controlador no toca el SDK de AWS: cada metodo delega en una operacion
 * de {@link ColaAdminService}.
 */
@RestController
@RequestMapping("/admin/colas")
public class ColaController {

    private final ColaAdminService colaAdminService;

    public ColaController(ColaAdminService colaAdminService) {
        this.colaAdminService = colaAdminService;
    }

    @GetMapping
    public List<ColaDto.Resumen> listar() {
        return colaAdminService.listarColas();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ColaDto.Resumen crear(@Valid @RequestBody ColaDto.CrearCola peticion) {
        return colaAdminService.crearCola(peticion);
    }

    /** Estado de las tres colas declaradas, para la vista de monitoreo. */
    @GetMapping("/estado")
    public List<ColaDto.Estado> estadoDeDeclaradas() {
        return colaAdminService.estadoDeColasDeclaradas();
    }

    @GetMapping("/{nombre}")
    public ColaDto.Estado estado(@PathVariable String nombre) {
        return colaAdminService.estadoDeCola(nombre);
    }

    @DeleteMapping("/{nombre}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable String nombre) {
        colaAdminService.eliminarCola(nombre);
    }

    /**
     * Vacia la cola. Es parte de la politica de limpieza: SQS solo admite una
     * purga cada 60 segundos.
     */
    @PostMapping("/{nombre}/purga")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void purgar(@PathVariable String nombre) {
        colaAdminService.purgarCola(nombre);
    }
}
