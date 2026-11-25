package com.pedidos360.streaming.ventas;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /tablero/ventas?dia=2026-11-24}: tablero de ventas del dia (hoy si
 * no se indica). El panel del frontend lo consulta cada pocos segundos para
 * mostrar como cambia al llegar cada evento.
 */
@RestController
@RequestMapping("/tablero/ventas")
public class TableroVentasController {

    private final TableroVentasConsulta consulta;

    public TableroVentasController(TableroVentasConsulta consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public TableroVentasConsulta.TableroVentas tablero(@RequestParam(required = false) String dia) {
        return consulta.delDia(dia);
    }
}
