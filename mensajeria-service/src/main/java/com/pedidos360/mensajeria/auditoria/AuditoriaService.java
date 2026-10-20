package com.pedidos360.mensajeria.auditoria;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pedidos360.mensajeria.comun.EventoPedido;

/**
 * Dominio de auditoria: bitacora de todo lo que le pasa a un pedido.
 *
 * <p>Es el unico dominio que recibe absolutamente todos los eventos, porque su
 * cola esta suscrita al topico sin filtro. Eso es lo que hace de esta cola la
 * demostracion del patron fanout: cada evento publicado aparece aqui, incluso
 * los que las otras dos colas descartan.
 *
 * <p>Separar la auditoria en su propia cola tiene una razon operativa: no puede
 * perder eventos, pero tampoco importa si se atrasa. Mezclada con las
 * notificaciones competiria por los mismos reintentos y un problema del
 * proveedor de correo atrasaria la bitacora.
 */
@Service
public class AuditoriaService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Ultimas entradas, para poder mostrarlas sin abrir el log. */
    private static final int MAXIMO_EN_MEMORIA = 200;

    private final Deque<String> bitacora = new ArrayDeque<>();

    public void registrar(EventoPedido evento) {
        String entrada = "%s | pedido #%d | %s -> %s | cliente=%s | idEvento=%s".formatted(
                FORMATO_FECHA.format(evento.ocurridoEn()),
                evento.pedidoId(),
                evento.estadoAnterior() == null ? "(nuevo)" : evento.estadoAnterior(),
                evento.estadoNuevo(),
                evento.clienteNombre(),
                evento.idEvento());

        synchronized (bitacora) {
            bitacora.addFirst(entrada);
            while (bitacora.size() > MAXIMO_EN_MEMORIA) {
                bitacora.removeLast();
            }
        }

        log.info("AUDITORIA | {}", entrada);
    }

    public List<String> ultimasEntradas() {
        synchronized (bitacora) {
            return List.copyOf(bitacora);
        }
    }
}
