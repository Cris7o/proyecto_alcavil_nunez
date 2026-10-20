package com.pedidos360.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.admin.dto.MonitoreoDto;
import com.pedidos360.admin.service.MonitoreoService;

/**
 * API REST de monitoreo.
 *
 * <pre>
 * GET  /admin/monitoreo/metricas   las cuatro metricas de cada cola, interpretadas
 * GET  /admin/monitoreo/alarmas    estado de las alarmas de las DLQ
 * POST /admin/monitoreo/alarmas    declara o actualiza las alarmas
 * </pre>
 *
 * <p>Las metricas vienen de CloudWatch, que las publica cada 5 minutos. Para
 * ver el estado al instante conviene mirar
 * {@code GET /admin/colas/estado}, que lee los atributos de la cola en vivo.
 */
@RestController
@RequestMapping("/admin/monitoreo")
public class MonitoreoController {

    private final MonitoreoService monitoreoService;

    public MonitoreoController(MonitoreoService monitoreoService) {
        this.monitoreoService = monitoreoService;
    }

    @GetMapping("/metricas")
    public List<MonitoreoDto.MetricasCola> metricas() {
        return monitoreoService.metricas();
    }

    @GetMapping("/alarmas")
    public List<MonitoreoDto.Alarma> alarmas() {
        return monitoreoService.estadoDeAlarmas();
    }

    @PostMapping("/alarmas")
    public MonitoreoDto.ResultadoDeclaracionAlarmas declararAlarmas() {
        return monitoreoService.declararAlarmas();
    }
}
