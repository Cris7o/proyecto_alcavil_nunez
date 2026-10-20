package com.pedidos360.admin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Clientes de AWS y del canal de control, en un solo lugar.
 *
 * <p>Los servicios de administracion reciben estos clientes ya construidos y no
 * saben de donde salen las credenciales ni la region. Eso es lo que mantiene la
 * configuracion separada de la logica: {@code ColaAdminService} no contiene una
 * sola linea de inicializacion del SDK.
 *
 * <p>Las credenciales las resuelve {@link DefaultCredentialsProvider}, que las
 * busca en variables de entorno, en {@code ~/.aws/credentials} y en el perfil de
 * instancia de EC2, en ese orden. Por eso no hay ninguna clave en el
 * repositorio: en el equipo se exportan las del Learner Lab, y en EC2 las
 * aporta el rol de la instancia.
 */
@Configuration
@EnableConfigurationProperties(MensajeriaProperties.class)
public class AwsClientesConfig {

    private final Region region;

    public AwsClientesConfig(MensajeriaProperties propiedades) {
        this.region = Region.of(propiedades.getRegion());
    }

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public SnsClient snsClient() {
        return SnsClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public CloudWatchClient cloudWatchClient() {
        return CloudWatchClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /** Cliente HTTP con el que se le habla al microservicio de consumidores. */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
