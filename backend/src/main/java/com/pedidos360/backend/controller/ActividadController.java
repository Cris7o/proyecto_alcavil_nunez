package com.pedidos360.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.backend.dto.ActividadLoteRequest;
import com.pedidos360.backend.service.ActividadService;
import com.pedidos360.backend.service.UsuarioService;
import com.pedidos360.backend.streaming.PublicadorStreamActividad.ResultadoLote;

import jakarta.validation.Valid;

/**
 * {@code POST /api/actividad}: recibe un lote de interacciones del cliente y lo
 * publica en el stream de actividad.
 *
 * <p>Responde 202 con el resultado del lote (cuantos se publicaron y cuantos
 * rechazo Kinesis), para que el navegador sepa si debe reenviar.
 */
@RestController
public class ActividadController {

    private final ActividadService actividadService;
    private final UsuarioService usuarioService;

    public ActividadController(ActividadService actividadService, UsuarioService usuarioService) {
        this.actividadService = actividadService;
        this.usuarioService = usuarioService;
    }

    @PostMapping("/api/actividad")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResultadoLote registrar(@Valid @RequestBody ActividadLoteRequest lote, Authentication authentication) {
        return actividadService.registrar(lote, usuarioService.obtenerOCrearActual(authentication));
    }
}
