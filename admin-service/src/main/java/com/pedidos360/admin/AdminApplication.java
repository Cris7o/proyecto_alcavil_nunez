package com.pedidos360.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservicio administrador de la mensajeria.
 *
 * <p>No participa del flujo de pedidos: su unico trabajo es operar la
 * infraestructura de mensajeria (crear y eliminar colas y topicos, gestionar
 * suscripciones, consultar estado, pausar y reanudar consumidores, y
 * reprocesar o purgar las DLQ).
 */
@SpringBootApplication
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
