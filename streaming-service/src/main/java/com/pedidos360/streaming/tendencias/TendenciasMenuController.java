package com.pedidos360.streaming.tendencias;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /tablero/tendencias}: lo mas agregado, buscado y filtrado en la ultima hora. */
@RestController
@RequestMapping("/tablero/tendencias")
public class TendenciasMenuController {

    private final TendenciasMenuConsulta consulta;

    public TendenciasMenuController(TendenciasMenuConsulta consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public TendenciasMenuConsulta.Tendencias tendencias() {
        return consulta.ultimaHora();
    }
}
