package com.pedidos360.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.pedidos360.admin.config.StreamingProperties;

/**
 * Plano de control de las instancias de streaming-service.
 *
 * <p>La vista "global" del reparto de shards sale de la tabla de coordinacion
 * ({@link StreamAdminService#inspeccionarAplicacion}). Esta clase agrega lo que
 * solo sabe cada instancia: cuantos eventos proceso, duplicados, derivados y
 * reintentos por shard, y le reenvia las fallas inducidas para la demostracion.
 * Una instancia caida no hace fallar la operacion: se informa y se sigue.
 */
@Service
public class StreamConsumidoresService {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumidoresService.class);

    private final RestClient restClient;
    private final StreamingProperties propiedades;

    public StreamConsumidoresService(RestClient.Builder restClientBuilder, StreamingProperties propiedades) {
        this.restClient = restClientBuilder.build();
        this.propiedades = propiedades;
    }

    public List<Map<String, Object>> estadoInstancias() {
        List<Map<String, Object>> respuestas = new ArrayList<>();
        for (String base : propiedades.getConsumidores().getInstancias()) {
            respuestas.add(llamar(base, () -> restClient.get().uri(base + "/control/estado").retrieve()
                    .body(Object.class)));
        }
        return respuestas;
    }

    public List<Map<String, Object>> inducirFalla(String aplicacion, String modo) {
        propiedades.aplicacion(aplicacion);
        log.warn("Induciendo falla {} en {} en todas las instancias", modo, aplicacion);
        List<Map<String, Object>> respuestas = new ArrayList<>();
        for (String base : propiedades.getConsumidores().getInstancias()) {
            respuestas.add(llamar(base, () -> restClient.put()
                    .uri(base + "/control/fallas/" + aplicacion)
                    .body(Map.of("modo", modo))
                    .retrieve()
                    .body(Object.class)));
        }
        return respuestas;
    }

    private Map<String, Object> llamar(String base, java.util.function.Supplier<Object> llamada) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("instancia", base);
        try {
            respuesta.put("alcanzable", true);
            respuesta.put("respuesta", llamada.get());
        } catch (Exception e) {
            log.debug("La instancia {} no respondio: {}", base, e.getMessage());
            respuesta.put("alcanzable", false);
            respuesta.put("error", String.valueOf(e.getMessage()));
        }
        return respuesta;
    }
}
