package com.pedidos360.backend.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Configuracion de mensajeria del servicio de pedidos.
 *
 * <p>Toda la decision de "como se publica" vive aqui. La capa de negocio solo
 * recibe un {@link PublicadorEventos} ya armado y no sabe si detras hay SNS,
 * un log o cualquier otra cosa.
 */
@Configuration
@EnableConfigurationProperties(MensajeriaProperties.class)
public class MensajeriaConfig {

    @Bean
    public PublicadorEventos publicadorEventos(MensajeriaProperties propiedades,
            ObjectMapper objectMapper, SnsClient snsClient) {

        if (!propiedades.isHabilitada()) {
            return new PublicadorEventosRegistrado();
        }
        return new SnsPublicadorEventos(snsClient, objectMapper, propiedades);
    }
}
