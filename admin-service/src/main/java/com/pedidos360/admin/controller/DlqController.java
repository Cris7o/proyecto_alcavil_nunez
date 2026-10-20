package com.pedidos360.admin.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.admin.config.MensajeriaProperties;
import com.pedidos360.admin.dto.ColaDto;
import com.pedidos360.admin.service.ColaAdminService;

/**
 * API REST de colas de mensajes muertos.
 *
 * <pre>
 * GET  /admin/dlq/{id}                 inspecciona la DLQ sin consumirla
 * POST /admin/dlq/{id}/reproceso       devuelve los mensajes a su cola de origen
 * POST /admin/dlq/{id}/purga           vacia la DLQ
 * </pre>
 *
 * <p>El {@code id} es el identificador logico de la cola
 * ({@code notificaciones}, {@code documentos} o {@code auditoria}), no el
 * nombre de la DLQ en AWS: quien opera la API no tiene por que saber como se
 * llaman los recursos, y el nombre sale de la configuracion.
 */
@RestController
@RequestMapping("/admin/dlq")
public class DlqController {

    private final ColaAdminService colaAdminService;
    private final MensajeriaProperties propiedades;

    public DlqController(ColaAdminService colaAdminService, MensajeriaProperties propiedades) {
        this.colaAdminService = colaAdminService;
        this.propiedades = propiedades;
    }

    /**
     * Devuelve los mensajes de la DLQ con su id de evento, el intento en que se
     * rindieron y la causa, sin borrarlos.
     */
    @GetMapping("/{id}")
    public List<ColaDto.MensajeEnDlq> inspeccionar(
            @PathVariable String id,
            @RequestParam(defaultValue = "10") int maximo) {
        return colaAdminService.inspeccionarDlq(nombreDlq(id), maximo);
    }

    /**
     * Devuelve los mensajes de la DLQ a su cola de origen. Es la operacion que
     * se usa despues de corregir la causa de la falla.
     */
    @PostMapping("/{id}/reproceso")
    public ColaDto.ResultadoReproceso reprocesar(
            @PathVariable String id,
            @RequestParam(defaultValue = "10") int maximo) {
        MensajeriaProperties.Cola cola = propiedades.colaPorId(id);
        return colaAdminService.reprocesarDlq(cola.getDlq().getNombre(), cola.getNombre(), maximo);
    }

    /**
     * Descarta todo lo que haya en la DLQ. Es la politica de limpieza para los
     * mensajes que ya se diagnosticaron y no vale la pena reprocesar.
     */
    @PostMapping("/{id}/purga")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void purgar(@PathVariable String id) {
        colaAdminService.purgarCola(nombreDlq(id));
    }

    private String nombreDlq(String id) {
        return propiedades.colaPorId(id).getDlq().getNombre();
    }
}
