package com.pedidos360.backend.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pedidos360.backend.dto.ActividadLoteRequest;
import com.pedidos360.backend.model.Usuario;
import com.pedidos360.backend.streaming.EventoActividad;
import com.pedidos360.backend.streaming.PublicadorStreamActividad;
import com.pedidos360.backend.streaming.PublicadorStreamActividad.ResultadoLote;
import com.pedidos360.backend.streaming.StreamingProperties;

/**
 * Convierte la actividad que envia el navegador en eventos del stream.
 *
 * <p>El {@code clienteId} lo pone el servidor a partir del JWT, nunca el
 * navegador: asi un cliente no puede atribuir actividad a otro.
 */
@Service
public class ActividadService {

    private final PublicadorStreamActividad publicador;
    private final StreamingProperties propiedades;

    public ActividadService(PublicadorStreamActividad publicador, StreamingProperties propiedades) {
        this.publicador = publicador;
        this.propiedades = propiedades;
    }

    public ResultadoLote registrar(ActividadLoteRequest lote, Usuario usuario) {
        int maximo = propiedades.getLote().getMaxEventosPorPeticion();
        if (lote.eventos().size() > maximo) {
            throw new IllegalArgumentException("Se aceptan hasta " + maximo + " eventos por peticion");
        }

        List<EventoActividad> eventos = lote.eventos().stream()
                .map(interaccion -> new EventoActividad(
                        EventoActividad.VERSION_ACTUAL,
                        interaccion.idEvento() != null ? interaccion.idEvento() : UUID.randomUUID().toString(),
                        interaccion.tipo(),
                        lote.sesionId(),
                        usuario.getId(),
                        interaccion.productoId(),
                        interaccion.productoNombre(),
                        interaccion.categoria(),
                        interaccion.termino(),
                        interaccion.ocurridoEn()))
                .toList();

        return publicador.publicarLote(eventos);
    }
}
