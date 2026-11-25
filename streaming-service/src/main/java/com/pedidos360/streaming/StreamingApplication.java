package com.pedidos360.streaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aplicaciones consumidoras de Kinesis Data Streams de Pedido360.
 *
 * <p>Cada instancia de este proceso ejecuta las aplicaciones activas en su
 * configuracion. Varias instancias con los mismos nombres de aplicacion se
 * reparten los shards automaticamente a traves de la tabla de coordinacion.
 */
@SpringBootApplication
public class StreamingApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamingApplication.class, args);
    }
}
