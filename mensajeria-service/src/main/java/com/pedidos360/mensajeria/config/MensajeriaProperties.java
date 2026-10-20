package com.pedidos360.mensajeria.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Unico lugar del microservicio donde aparecen nombres de colas y topicos.
 *
 * <p>Las clases de dominio ({@code NotificacionService}, {@code DocumentoService},
 * {@code AuditoriaService}) no conocen ningun nombre de recurso de AWS. Los
 * consumidores resuelven el nombre de su cola desde esta configuracion mediante
 * un placeholder en la anotacion {@code @SqsListener}.
 *
 * <p>Se alimenta del bloque {@code pedido360.mensajeria} de {@code application.yml}.
 */
@ConfigurationProperties(prefix = "pedido360.mensajeria")
public class MensajeriaProperties {

    private final Topico topico = new Topico();
    private final Colas colas = new Colas();

    public Topico getTopico() {
        return topico;
    }

    public Colas getColas() {
        return colas;
    }

    /** Topico del que cuelgan las tres suscripciones. */
    public static class Topico {

        private String nombre;

        public String getNombre() {
            return nombre;
        }

        public void setNombre(String nombre) {
            this.nombre = nombre;
        }
    }

    /**
     * Las tres colas del sistema, una por dominio funcional. Se declaran como
     * campos y no como mapa para que no haya claves escritas a mano en el
     * codigo que las recorre.
     */
    public static class Colas {

        private final Cola notificaciones = new Cola();
        private final Cola documentos = new Cola();
        private final Cola auditoria = new Cola();

        public Cola getNotificaciones() {
            return notificaciones;
        }

        public Cola getDocumentos() {
            return documentos;
        }

        public Cola getAuditoria() {
            return auditoria;
        }

        /** Para recorrer las tres colas sin repetir la lista en cada servicio. */
        public List<Cola> todas() {
            return List.of(notificaciones, documentos, auditoria);
        }
    }

    /**
     * Una cola y su cola de mensajes muertos.
     *
     * <p>{@code maxReintentos} es el limite que aplica el consumidor antes de
     * derivar el mensaje a la DLQ con su causa. Es deliberadamente menor que el
     * {@code maxReceiveCount} de la Redrive Policy: el consumidor deriva primero
     * para poder adjuntar la causa, y la Redrive Policy de AWS queda como red de
     * seguridad para el caso en que el proceso muera antes de alcanzar a hacerlo.
     */
    public static class Cola {

        /** Identificador logico del consumidor, usado para pausarlo y reanudarlo. */
        private String id;

        /** Nombre de la cola principal en SQS. */
        private String nombre;

        /** Nombre de la cola de mensajes muertos asociada. */
        private String dlq;

        /** Intentos que hace el consumidor antes de derivar el mensaje a la DLQ. */
        private int maxReintentos = 3;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getNombre() {
            return nombre;
        }

        public void setNombre(String nombre) {
            this.nombre = nombre;
        }

        public String getDlq() {
            return dlq;
        }

        public void setDlq(String dlq) {
            this.dlq = dlq;
        }

        public int getMaxReintentos() {
            return maxReintentos;
        }

        public void setMaxReintentos(int maxReintentos) {
            this.maxReintentos = maxReintentos;
        }
    }
}
