package com.pedidos360.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.pedidos360.admin.config.StreamingProperties;
import com.pedidos360.admin.service.StreamAdminService;

/**
 * El contexto levanta sin tocar AWS y la declaracion de streaming cumple lo que
 * pide la evaluacion: dos streams de negocio de 3 shards, un stream de
 * fallidos, y dos aplicaciones consumidoras sobre el mismo stream.
 */
@SpringBootTest(properties = {
        "pedido360.mensajeria.declarar-al-iniciar=false",
        "pedido360.streaming.declarar-al-iniciar=false" })
class StreamingDeclaradoTest {

    @Autowired
    private StreamingProperties propiedades;

    @Autowired
    private StreamAdminService streamAdmin;

    @Test
    void dosStreamsDeNegocioDeTresShardsMasElDeFallidos() {
        assertThat(propiedades.stream("pedidos").getShards()).isEqualTo(3);
        assertThat(propiedades.stream("actividad").getShards()).isEqualTo(3);
        assertThat(propiedades.stream("fallidos").getNombre()).isEqualTo("pedido360-fallidos");
        assertThat(propiedades.getStreams()).allSatisfy(stream -> {
            assertThat(stream.getJustificacion()).isNotBlank();
            assertThat(stream.getRetencionHoras()).isBetween(24, 8760);
            assertThat(stream.getModoCapacidad()).isEqualTo("PROVISIONED");
        });
        // Los shards difieren segun el flujo: el de fallidos solo necesita uno.
        assertThat(propiedades.stream("fallidos").getShards()).isEqualTo(1);
    }

    @Test
    void dosAplicacionesConsumidorasSobreElStreamDePedidos() {
        assertThat(propiedades.getAplicaciones())
                .filteredOn(aplicacion -> aplicacion.getStream().equals("pedidos"))
                .hasSize(2);
        assertThat(propiedades.getAlarma().getMetrica()).isEqualTo("GetRecords.IteratorAgeMilliseconds");
        assertThat(streamAdmin).isNotNull();
    }
}
