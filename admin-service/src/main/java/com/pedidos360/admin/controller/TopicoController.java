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

import com.pedidos360.admin.dto.TopicoDto;
import com.pedidos360.admin.service.ColaAdminService;
import com.pedidos360.admin.service.PublicacionPruebaService;

import jakarta.validation.Valid;

/**
 * API REST de topicos y suscripciones.
 *
 * <pre>
 * GET    /admin/topicos                              lista los topicos
 * POST   /admin/topicos                              crea un topico
 * DELETE /admin/topicos/{nombre}                     elimina un topico
 * GET    /admin/topicos/{nombre}/suscripciones       lista las suscripciones
 * POST   /admin/topicos/suscripciones                suscribe una cola
 * PUT    /admin/topicos/suscripciones/filtro         cambia el filtro
 * DELETE /admin/topicos/suscripciones                elimina una suscripcion
 * POST   /admin/topicos/publicaciones-prueba         publica un evento sintetico
 * </pre>
 *
 * <p>Las suscripciones se identifican por su ARN, que es como las identifica
 * SNS; por eso van como parametro y no en la ruta: un ARN lleva dos puntos y
 * barras que no conviene meter en un segmento de ruta.
 */
@RestController
@RequestMapping("/admin/topicos")
public class TopicoController {

    private final ColaAdminService colaAdminService;
    private final PublicacionPruebaService publicacionPruebaService;

    public TopicoController(ColaAdminService colaAdminService,
            PublicacionPruebaService publicacionPruebaService) {
        this.colaAdminService = colaAdminService;
        this.publicacionPruebaService = publicacionPruebaService;
    }

    @GetMapping
    public List<TopicoDto.Resumen> listar() {
        return colaAdminService.listarTopicos();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TopicoDto.Resumen crear(@Valid @RequestBody TopicoDto.CrearTopico peticion) {
        return colaAdminService.crearTopico(peticion.nombre());
    }

    @DeleteMapping("/{nombre}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable String nombre) {
        colaAdminService.eliminarTopico(nombre);
    }

    @GetMapping("/{nombre}/suscripciones")
    public List<TopicoDto.ResumenSuscripcion> listarSuscripciones(@PathVariable String nombre) {
        return colaAdminService.listarSuscripciones(nombre);
    }

    @PostMapping("/suscripciones")
    @ResponseStatus(HttpStatus.CREATED)
    public TopicoDto.ResumenSuscripcion suscribir(
            @Valid @RequestBody TopicoDto.CrearSuscripcion peticion) {
        return colaAdminService.suscribir(peticion);
    }

    /**
     * Cambia el filtro de una suscripcion en caliente. Un cuerpo con filtro
     * vacio la convierte en fanout.
     */
    @PutMapping("/suscripciones/filtro")
    public List<TopicoDto.ResumenSuscripcion> actualizarFiltro(
            @RequestParam String arn,
            @RequestParam String topico,
            @RequestBody TopicoDto.ActualizarFiltro peticion) {
        colaAdminService.actualizarFiltro(arn, peticion.filtro());
        return colaAdminService.listarSuscripciones(topico);
    }

    @DeleteMapping("/suscripciones")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarSuscripcion(@RequestParam String arn) {
        colaAdminService.eliminarSuscripcion(arn);
    }

    /**
     * Publica un evento sintetico en el topico. Es la forma rapida de demostrar
     * fanout, entrega selectiva y tolerancia a duplicados sin crear pedidos.
     */
    @PostMapping("/publicaciones-prueba")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TopicoDto.ResultadoPublicacion publicarPrueba(
            @Valid @RequestBody TopicoDto.EventoPrueba peticion) {
        return publicacionPruebaService.publicar(peticion);
    }
}
