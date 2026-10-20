package com.pedidos360.admin.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.pedidos360.admin.config.MensajeriaProperties;
import com.pedidos360.admin.config.MensajeriaProperties.Cola;
import com.pedidos360.admin.dto.ColaDto;
import com.pedidos360.admin.dto.TopicoDto;

/**
 * Aplica sobre AWS la topologia declarada en {@code application.yml}.
 *
 * <p>Es la pieza que convierte la declaracion en recursos reales: crea el
 * topico, las tres colas con su retencion y su visibility timeout, las tres DLQ
 * con su propia retencion, asocia cada DLQ con su cola mediante la Redrive
 * Policy, y suscribe cada cola al topico con el patron que le corresponde
 * (fanout o entrega selectiva).
 *
 * <p>Es idempotente: se puede ejecutar cuantas veces se quiera. Eso importa en
 * el Learner Lab, donde reconstruir el entorno es levantar este servicio en
 * lugar de repetir decenas de pasos en la consola.
 */
@Service
public class TopologiaService {

    private static final Logger log = LoggerFactory.getLogger(TopologiaService.class);

    private final ColaAdminService colaAdminService;
    private final MonitoreoService monitoreoService;
    private final MensajeriaProperties propiedades;

    public TopologiaService(ColaAdminService colaAdminService, MonitoreoService monitoreoService,
            MensajeriaProperties propiedades) {
        this.colaAdminService = colaAdminService;
        this.monitoreoService = monitoreoService;
        this.propiedades = propiedades;
    }

    /**
     * Declara la topologia al arrancar, si esta habilitado. Si falla, se deja
     * registrado pero no se impide el arranque: el servicio sigue sirviendo
     * para diagnosticar por que fallo.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void declararAlIniciar() {
        if (!propiedades.isDeclararAlIniciar()) {
            log.info("Declaracion automatica de la topologia deshabilitada. "
                    + "Se puede aplicar con POST /admin/topologia");
            return;
        }
        try {
            ColaDto.TopologiaDeclarada resultado = declarar();
            log.info("Topologia aplicada: topico '{}' con {} colas",
                    resultado.topico(), resultado.colas().size());
        } catch (Exception e) {
            log.error("No se pudo declarar la topologia al arrancar: {}. "
                    + "Revisa las credenciales de AWS y vuelve a intentar con POST /admin/topologia",
                    e.getMessage());
        }
    }

    /** Aplica la topologia completa y devuelve lo que quedo configurado. */
    public ColaDto.TopologiaDeclarada declarar() {
        String nombreTopico = propiedades.getTopico().getNombre();
        TopicoDto.Resumen topico = colaAdminService.crearTopico(nombreTopico);

        List<ColaDto.ColaDeclarada> declaradas = new ArrayList<>();

        for (Cola cola : propiedades.getColas()) {
            // 1. La cola y su DLQ, asociadas por Redrive Policy.
            ColaDto.Resumen resumen = colaAdminService.crearCola(new ColaDto.CrearCola(
                    cola.getNombre(),
                    cola.getRetencionSegundos(),
                    cola.getVisibilidadSegundos(),
                    cola.getEsperaLargaSegundos(),
                    cola.getDlq().getNombre(),
                    cola.getMaxReceiveCount()));

            // 2. La retencion propia de la DLQ, mas larga que la de la cola.
            colaAdminService.crearCola(new ColaDto.CrearCola(
                    cola.getDlq().getNombre(),
                    cola.getDlq().getRetencionSegundos(),
                    null, null, null, null));

            // 3. La suscripcion al topico, con su patron de enrutamiento.
            TopicoDto.ResumenSuscripcion suscripcion = colaAdminService.suscribir(
                    new TopicoDto.CrearSuscripcion(
                            nombreTopico,
                            cola.getNombre(),
                            cola.getSuscripcion().getFiltro(),
                            cola.getSuscripcion().isRawMessageDelivery()));

            declaradas.add(new ColaDto.ColaDeclarada(
                    cola.getId(),
                    cola.getNombre(),
                    resumen.url(),
                    cola.getRetencionSegundos(),
                    cola.getVisibilidadSegundos(),
                    cola.getEsperaLargaSegundos(),
                    cola.getMaxReceiveCount(),
                    cola.getJustificacion(),
                    cola.getDlq().getNombre(),
                    cola.getDlq().getRetencionSegundos(),
                    cola.getDlq().getJustificacion(),
                    suscripcion.arn(),
                    cola.getSuscripcion().getPatron(),
                    cola.getSuscripcion().getFiltro()));
        }

        // 4. Una alarma de CloudWatch por DLQ. Va al final porque las alarmas
        // apuntan a colas que ya tienen que existir.
        monitoreoService.declararAlarmas();

        return new ColaDto.TopologiaDeclarada(nombreTopico, topico.arn(), declaradas);
    }

    /**
     * Elimina la topologia completa. Existe para poder demostrar la creacion
     * desde cero, y porque la pauta pide poder eliminar colas y topicos.
     */
    public void eliminar() {
        for (Cola cola : propiedades.getColas()) {
            eliminarSinFallar(cola.getNombre());
            eliminarSinFallar(cola.getDlq().getNombre());
        }
        try {
            colaAdminService.eliminarTopico(propiedades.getTopico().getNombre());
        } catch (Exception e) {
            log.warn("No se pudo eliminar el topico: {}", e.getMessage());
        }
        log.warn("Topologia eliminada. Para recrearla: POST /admin/topologia");
    }

    private void eliminarSinFallar(String nombreCola) {
        try {
            colaAdminService.eliminarCola(nombreCola);
        } catch (Exception e) {
            log.warn("No se pudo eliminar la cola '{}': {}", nombreCola, e.getMessage());
        }
    }
}
