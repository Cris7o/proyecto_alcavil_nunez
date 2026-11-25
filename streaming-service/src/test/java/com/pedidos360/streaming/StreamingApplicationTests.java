package com.pedidos360.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.pedidos360.streaming.comun.ManejadorDominio;
import com.pedidos360.streaming.config.StreamingProperties;
import com.pedidos360.streaming.kcl.AplicacionesConsumidoras;

/**
 * El contexto levanta con la configuracion real y sin conectarse a AWS
 * ({@code consumir=false}): verifica el cableado y que cada aplicacion
 * declarada tenga su dominio.
 */
@SpringBootTest(properties = "pedido360.streaming.consumir=false")
class StreamingApplicationTests {

    @Autowired
    private StreamingProperties propiedades;

    @Autowired
    private List<ManejadorDominio<?>> manejadores;

    @Autowired
    private AplicacionesConsumidoras aplicaciones;

    @Test
    void cadaAplicacionDeclaradaTieneSuDominioYSuStream() {
        List<String> dominios = manejadores.stream().map(ManejadorDominio::dominio).toList();

        assertThat(propiedades.getAplicaciones()).hasSize(3).allSatisfy(aplicacion -> {
            assertThat(dominios).contains(aplicacion.getDominio());
            assertThat(propiedades.nombreStream(aplicacion.getStream())).startsWith("pedido360-");
        });
        assertThat(aplicaciones.isRunning()).isFalse();
    }

    @Test
    void dosAplicacionesDistintasLeenElMismoStreamDePedidos() {
        assertThat(propiedades.getAplicaciones())
                .filteredOn(aplicacion -> aplicacion.getStream().equals("pedidos"))
                .extracting(StreamingProperties.Aplicacion::getNombre)
                .containsExactlyInAnyOrder("pedido360-tablero-ventas", "pedido360-seguimiento-cocina");
    }
}
