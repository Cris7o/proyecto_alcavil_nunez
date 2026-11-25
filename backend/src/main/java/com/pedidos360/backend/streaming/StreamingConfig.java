package com.pedidos360.backend.streaming;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente Kinesis y productores del servicio de pedidos.
 *
 * <p>Toda la decision de "como se publica en un stream" vive aqui: region,
 * credenciales, reintentos, timeouts y que implementacion usar. La capa de
 * negocio recibe los publicadores ya armados.
 *
 * <p>Con {@code pedido360.streaming.habilitado=false} (perfil local) los
 * eventos se registran en el log en vez de enviarse, para probar el flujo sin
 * credenciales de AWS.
 */
@Configuration
@EnableConfigurationProperties(StreamingProperties.class)
public class StreamingConfig {

    private static final Logger log = LoggerFactory.getLogger(StreamingConfig.class);

    @Bean(destroyMethod = "close")
    public KinesisClient kinesisClient(StreamingProperties propiedades) {
        StreamingProperties.Cliente cliente = propiedades.getCliente();
        return KinesisClient.builder()
                .region(Region.of(propiedades.getRegion()))
                // Credenciales del entorno o del rol de la instancia EC2: nunca en el repositorio.
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(estrategia -> estrategia.maxAttempts(cliente.getMaxReintentosSdk() + 1))
                        .apiCallTimeout(Duration.ofMillis(cliente.getTimeoutLlamadaMs()))
                        .apiCallAttemptTimeout(Duration.ofMillis(cliente.getTimeoutIntentoMs()))
                        .build())
                .build();
    }

    @Bean
    public PublicadorStreamPedidos publicadorStreamPedidos(StreamingProperties propiedades, KinesisClient kinesis,
            ObjectMapper objectMapper) {
        if (!propiedades.isHabilitado()) {
            return evento -> log.info("Streaming deshabilitado, evento no publicado en {} | idEvento={} | tipo={} | pedidoId={}",
                    propiedades.getStreams().getPedidos(), evento.idEvento(), evento.tipo(), evento.pedidoId());
        }
        return new KinesisPublicadorPedidos(kinesis, objectMapper, propiedades.getStreams().getPedidos());
    }

    @Bean
    public PublicadorStreamActividad publicadorStreamActividad(StreamingProperties propiedades,
            KinesisClient kinesis, ObjectMapper objectMapper) {
        if (!propiedades.isHabilitado()) {
            return eventos -> {
                log.info("Streaming deshabilitado, {} eventos de actividad no publicados en {}", eventos.size(),
                        propiedades.getStreams().getActividad());
                return new PublicadorStreamActividad.ResultadoLote(eventos.size(), 0, 0, 0, Map.of());
            };
        }
        return new KinesisPublicadorActividad(kinesis, objectMapper, propiedades.getStreams().getActividad(),
                propiedades.getLote().getMaxIntentos(), propiedades.getLote().getEsperaBaseMs());
    }
}
