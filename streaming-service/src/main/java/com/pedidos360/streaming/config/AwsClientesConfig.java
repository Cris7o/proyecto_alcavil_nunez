package com.pedidos360.streaming.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pedidos360.streaming.comun.RepositorioEstado;
import com.pedidos360.streaming.kcl.KinesisPublicadorFallidos;
import com.pedidos360.streaming.kcl.PoliticaReintentos;
import com.pedidos360.streaming.kcl.PublicadorFallidos;
import com.pedidos360.streaming.persistencia.DynamoRepositorioEstado;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.kinesis.common.KinesisClientUtil;
import tools.jackson.databind.ObjectMapper;

/**
 * Clientes de AWS del servicio de streaming, en un solo lugar.
 *
 * <p>Las credenciales las resuelve {@link DefaultCredentialsProvider}: variables
 * de entorno, {@code ~/.aws/credentials} o el perfil de instancia de EC2. No hay
 * ninguna clave en el repositorio.
 *
 * <p>Hay dos familias de clientes:
 * <ul>
 * <li><b>Asincronicos</b> (Kinesis, DynamoDB, CloudWatch): los usa la KCL para
 * leer los shards, mantener la tabla de coordinacion y publicar sus metricas.
 * <li><b>Sincronicos</b> (Kinesis, DynamoDB): los usa el codigo propio para el
 * estado derivado y para publicar en el stream de fallidos, con reintentos y
 * timeouts configurados.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(StreamingProperties.class)
public class AwsClientesConfig {

    private final StreamingProperties propiedades;
    private final Region region;

    public AwsClientesConfig(StreamingProperties propiedades) {
        this.propiedades = propiedades;
        this.region = Region.of(propiedades.getRegion());
    }

    /** Reintentos del SDK (con espera exponencial) y timeouts de cada llamada. */
    private ClientOverrideConfiguration reintentosYTimeouts() {
        StreamingProperties.Cliente cliente = propiedades.getCliente();
        return ClientOverrideConfiguration.builder()
                .retryStrategy(estrategia -> estrategia.maxAttempts(cliente.getMaxReintentosSdk() + 1))
                .apiCallTimeout(Duration.ofMillis(cliente.getTimeoutLlamadaMs()))
                .apiCallAttemptTimeout(Duration.ofMillis(cliente.getTimeoutIntentoMs()))
                .build();
    }

    // ---------------------------------------------------------------- KCL

    @Bean(destroyMethod = "close")
    public KinesisAsyncClient kinesisAsyncClient() {
        // KinesisClientUtil ajusta el cliente para la KCL (HTTP/2 y limites de
        // conexiones adecuados para leer varios shards en paralelo).
        return KinesisClientUtil.createKinesisAsyncClient(KinesisAsyncClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create()));
    }

    @Bean(destroyMethod = "close")
    public DynamoDbAsyncClient dynamoDbAsyncClient() {
        return DynamoDbAsyncClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean(destroyMethod = "close")
    public CloudWatchAsyncClient cloudWatchAsyncClient() {
        return CloudWatchAsyncClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    // ------------------------------------------------------- codigo propio

    @Bean(destroyMethod = "close")
    public KinesisClient kinesisClient() {
        return KinesisClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(reintentosYTimeouts())
                .build();
    }

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(reintentosYTimeouts())
                .build();
    }

    @Bean
    public RepositorioEstado repositorioEstado(DynamoDbClient dynamoDbClient) {
        return new DynamoRepositorioEstado(dynamoDbClient, propiedades.getTablaEstado(),
                propiedades.getErrores().getRecordarProcesadosHoras());
    }

    @Bean
    public PublicadorFallidos publicadorFallidos(KinesisClient kinesisClient, ObjectMapper objectMapper) {
        return new KinesisPublicadorFallidos(kinesisClient, objectMapper, propiedades.getStreams().getFallidos());
    }

    @Bean
    public PoliticaReintentos politicaReintentos() {
        StreamingProperties.Errores errores = propiedades.getErrores();
        return new PoliticaReintentos(errores.getMaxIntentos(), errores.getEsperaBaseMs(),
                errores.getEsperaMaximaMs());
    }
}
