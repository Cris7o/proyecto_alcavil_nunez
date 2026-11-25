package com.pedidos360.streaming.cocina;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /tablero/cocina}: pedidos en curso, atrasados y tiempos por etapa. */
@RestController
@RequestMapping("/tablero/cocina")
public class SeguimientoCocinaController {

    private final SeguimientoCocinaConsulta consulta;

    public SeguimientoCocinaController(SeguimientoCocinaConsulta consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public SeguimientoCocinaConsulta.SeguimientoCocina seguimiento() {
        return consulta.actual();
    }
}
