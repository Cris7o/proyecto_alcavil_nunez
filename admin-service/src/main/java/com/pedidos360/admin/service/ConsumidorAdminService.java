package com.pedidos360.admin.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.pedidos360.admin.config.MensajeriaProperties;

/**
 * Pausa y reanuda consumidores que viven en otro proceso.
 *
 * <p>Los contenedores de los consumidores estan dentro del
 * {@code mensajeria-service}, no aqui, asi que este servicio no los apaga: les
 * reenvia la orden por HTTP a cada instancia registrada. Es el patron de un
 * plano de control, y es la unica forma correcta de hacerlo sin acoplar los dos
 * microservicios en un mismo despliegue.
 *
 * <p>La orden va a <em>todas</em> las instancias: con dos instancias leyendo la
 * misma cola, pausar solo una no detendria el consumo.
 */
@Service
public class ConsumidorAdminService {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorAdminService.class);

    private final RestClient restClient;
    private final MensajeriaProperties propiedades;

    public ConsumidorAdminService(RestClient.Builder restClientBuilder,
            MensajeriaProperties propiedades) {
        this.restClient = restClientBuilder.build();
        this.propiedades = propiedades;
    }

    /** Estado de los consumidores de cada instancia. */
    public List<Map<String, Object>> estado() {
        return consultarTodas("", "GET", null);
    }

    /**
     * Actividad reciente de todas las instancias, en una sola lista ordenada de
     * lo mas nuevo a lo mas viejo.
     *
     * <p>Juntar las dos instancias es justamente lo que deja ver como SQS
     * reparte el trabajo: en la lista aparecen entremezcladas las lineas de una
     * y de la otra, cada una marcada con su nombre.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> actividad() {
        List<Map<String, Object>> todas = new ArrayList<>();

        for (String base : propiedades.getConsumidores().getInstancias()) {
            String url = base + "/interno/consumidores/actividad";
            try {
                List<Map<String, Object>> deLaInstancia = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(List.class);

                if (deLaInstancia != null) {
                    todas.addAll(deLaInstancia);
                }
            } catch (Exception e) {
                // Una instancia caida no vacia la lista: se muestra lo que haya
                // reportado la otra. Ese es el escenario que se demuestra.
                log.debug("La instancia {} no reporto actividad: {}", base, e.getMessage());
            }
        }

        todas.sort(Comparator.comparing(
                (Map<String, Object> linea) -> String.valueOf(linea.get("momento")))
                .reversed());

        return todas;
    }

    public List<Map<String, Object>> pausar(String idConsumidor) {
        log.info("Pausando el consumidor '{}' en todas las instancias", idConsumidor);
        return consultarTodas("/" + idConsumidor + "/pausar", "POST", null);
    }

    public List<Map<String, Object>> reanudar(String idConsumidor) {
        log.info("Reanudando el consumidor '{}' en todas las instancias", idConsumidor);
        return consultarTodas("/" + idConsumidor + "/reanudar", "POST", null);
    }

    /** Induce una falla para demostrar las rutas de reintento y de DLQ. */
    public List<Map<String, Object>> inducirFalla(String idConsumidor, String modo) {
        log.warn("Induciendo falla '{}' en el consumidor '{}'", modo, idConsumidor);
        return consultarTodas("/" + idConsumidor + "/falla-inducida?modo=" + modo, "POST", null);
    }

    /**
     * Llama a todas las instancias y junta las respuestas. Una instancia caida
     * no hace fallar la operacion: se reporta su error y se sigue con el resto,
     * porque justamente el escenario que se demuestra es que una se cae.
     */
    private List<Map<String, Object>> consultarTodas(String ruta, String metodo, Object cuerpo) {
        List<Map<String, Object>> respuestas = new ArrayList<>();

        if (propiedades.getConsumidores().getInstancias().isEmpty()) {
            log.warn("No hay instancias de consumidores configuradas en "
                    + "pedido360.mensajeria.consumidores.instancias");
        }

        for (String base : propiedades.getConsumidores().getInstancias()) {
            String url = base + "/interno/consumidores" + ruta;
            try {
                Object resultado = "POST".equals(metodo)
                        ? restClient.post().uri(url).retrieve().body(Object.class)
                        : restClient.get().uri(url).retrieve().body(Object.class);

                respuestas.add(Map.of(
                        "instancia", base,
                        "alcanzable", true,
                        "respuesta", resultado == null ? List.of() : resultado));

            } catch (Exception e) {
                log.warn("La instancia {} no respondio: {}", base, e.getMessage());
                respuestas.add(Map.of(
                        "instancia", base,
                        "alcanzable", false,
                        "error", String.valueOf(e.getMessage())));
            }
        }
        return respuestas;
    }
}
