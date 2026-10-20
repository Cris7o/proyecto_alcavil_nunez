package com.pedidos360.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.admin.config.MensajeriaProperties;
import com.pedidos360.admin.dto.ColaDto;
import com.pedidos360.admin.service.TopologiaService;

/**
 * API REST de la topologia completa.
 *
 * <pre>
 * GET    /admin/topologia    muestra la topologia declarada y sus justificaciones
 * POST   /admin/topologia    la aplica sobre AWS (idempotente)
 * DELETE /admin/topologia    la elimina entera
 * </pre>
 *
 * <p>Aplicar la topologia crea el topico, las tres colas con su retencion y su
 * visibility timeout, las tres DLQ con su propia retencion, las Redrive Policy
 * que las asocian y las tres suscripciones con su patron de enrutamiento. Se
 * puede ejecutar cuantas veces se quiera.
 */
@RestController
@RequestMapping("/admin/topologia")
public class TopologiaController {

    private final TopologiaService topologiaService;
    private final MensajeriaProperties propiedades;

    public TopologiaController(TopologiaService topologiaService,
            MensajeriaProperties propiedades) {
        this.topologiaService = topologiaService;
        this.propiedades = propiedades;
    }

    /** La topologia tal como esta declarada en configuracion, sin consultar AWS. */
    @GetMapping
    public MensajeriaProperties declarada() {
        return propiedades;
    }

    @PostMapping
    public ColaDto.TopologiaDeclarada aplicar() {
        return topologiaService.declarar();
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar() {
        topologiaService.eliminar();
    }
}
