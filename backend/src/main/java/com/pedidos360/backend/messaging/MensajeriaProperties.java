package com.pedidos360.backend.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Unico lugar del servicio de pedidos donde aparecen nombres de recursos de
 * mensajeria. Ninguna clase de negocio conoce el nombre ni el ARN del topico:
 * solo depende de {@link PublicadorEventos}.
 *
 * <p>Se alimenta del bloque {@code pedido360.mensajeria} de
 * {@code application.properties}.
 */
@ConfigurationProperties(prefix = "pedido360.mensajeria")
public class MensajeriaProperties {

    /**
     * Permite apagar la publicacion para desarrollo local sin credenciales de
     * AWS. En el perfil {@code cognito} siempre va en true.
     */
    private boolean habilitada = true;

    private final Topico topico = new Topico();

    public boolean isHabilitada() {
        return habilitada;
    }

    public void setHabilitada(boolean habilitada) {
        this.habilitada = habilitada;
    }

    public Topico getTopico() {
        return topico;
    }

    /**
     * Topico de eventos de pedido. Es el unico punto de publicacion: el
     * servicio de pedidos no conoce las colas que cuelgan de el, y por eso se
     * pueden agregar o quitar consumidores sin tocar este microservicio.
     */
    public static class Topico {

        /** Nombre logico del topico, por ejemplo {@code pedido360-eventos-pedido}. */
        private String nombre;

        /**
         * ARN completo. Opcional: si viene vacio, el ARN se resuelve a partir
         * del nombre. Se inyecta por variable de entorno porque incluye el id
         * de cuenta, que cambia en cada sesion del Learner Lab.
         */
        private String arn;

        public String getNombre() {
            return nombre;
        }

        public void setNombre(String nombre) {
            this.nombre = nombre;
        }

        public String getArn() {
            return arn;
        }

        public void setArn(String arn) {
            this.arn = arn;
        }

        /** Destino a usar al publicar: el ARN si esta definido, el nombre si no. */
        public String destino() {
            return (arn == null || arn.isBlank()) ? nombre : arn;
        }
    }
}
