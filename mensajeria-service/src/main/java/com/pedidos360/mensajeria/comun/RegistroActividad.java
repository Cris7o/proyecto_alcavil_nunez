package com.pedidos360.mensajeria.comun;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ultimas cosas que le pasaron a los mensajes en esta instancia.
 *
 * <p>Existe para que el panel de administracion pueda mostrar en vivo lo que
 * esta ocurriendo, sin tener que leer la consola del servidor. Es la misma
 * informacion del log, pero consultable.
 *
 * <p>Se guarda en memoria y acotado: no es una bitacora, es una ventana a lo que
 * acaba de pasar. La bitacora persistente es la cola de auditoria.
 */
@Component
public class RegistroActividad {

    /** Cuantas lineas se recuerdan. Alcanza de sobra para una demostracion. */
    private static final int MAXIMO = 100;

    private final Deque<Actividad> actividades = new ArrayDeque<>();
    private final String instancia;

    public RegistroActividad(@Value("${pedido360.instancia:unica}") String instancia) {
        this.instancia = instancia;
    }

    public void registrar(String consumidor, Actividad.Resultado resultado, String idEvento,
            String tipoEvento, Long pedidoId, int intento, String detalle) {

        Actividad actividad = new Actividad(Instant.now(), instancia, consumidor, resultado,
                idEvento, tipoEvento, pedidoId, intento, detalle);

        synchronized (actividades) {
            actividades.addFirst(actividad);
            while (actividades.size() > MAXIMO) {
                actividades.removeLast();
            }
        }
    }

    /** De lo mas reciente a lo mas antiguo. */
    public List<Actividad> ultimas() {
        synchronized (actividades) {
            return List.copyOf(actividades);
        }
    }

    public String instancia() {
        return instancia;
    }
}
