package com.pedidos360.mensajeria.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Configuracion de mensajeria del microservicio de consumidores.
 *
 * <p>Esta clase y {@link MensajeriaProperties} son todo lo que este servicio
 * sabe de infraestructura. Los paquetes de dominio ({@code notificaciones},
 * {@code documentos}, {@code auditoria}) no importan nada del SDK de AWS: el
 * unico contacto con SQS esta en los consumidores, y se limita a la anotacion
 * {@code @SqsListener} y a delegar en {@code ProcesadorEventos}.
 *
 * <p>Los clientes de SQS los provee la autoconfiguracion de Spring Cloud AWS.
 * Las credenciales se resuelven del entorno (variables de entorno o
 * {@code ~/.aws/credentials} en el equipo, perfil de instancia en EC2): no hay
 * ninguna clave en el repositorio.
 */
@Configuration
@EnableConfigurationProperties(MensajeriaProperties.class)
public class SqsConfig {

    private static final Logger log = LoggerFactory.getLogger(SqsConfig.class);

    private final MensajeriaProperties propiedades;
    private final String instancia;

    public SqsConfig(MensajeriaProperties propiedades,
            @Value("${pedido360.instancia:unica}") String instancia) {
        this.propiedades = propiedades;
        this.instancia = instancia;
    }

    /**
     * Deja asentado al arrancar que colas atiende esta instancia. Con dos
     * instancias corriendo, este log es lo que permite distinguirlas en la
     * demostracion de disponibilidad.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registrarTopologiaConsumida() {
        log.info("Instancia '{}' escuchando el topico '{}' a traves de:",
                instancia, propiedades.getTopico().getNombre());

        propiedades.getColas().todas().forEach(cola -> log.info(
                "  consumidor '{}' -> cola '{}' (DLQ '{}', {} intentos antes de derivar)",
                cola.getId(), cola.getNombre(), cola.getDlq(), cola.getMaxReintentos()));
    }
}
