package com.pedidos360.admin.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Declaracion completa de la topologia de mensajeria.
 *
 * <p>Este es el archivo de configuracion que manda: topico, las tres colas, sus
 * DLQ con la Redrive Policy, las suscripciones con sus filtros y las politicas
 * de retencion. Los otros dos microservicios solo repiten los nombres que
 * necesitan; aqui esta la definicion entera, y es la que se aplica sobre AWS.
 *
 * <p>Ninguna clase de este microservicio tiene un nombre de cola o topico
 * escrito en el codigo: todas los reciben desde aqui.
 */
@ConfigurationProperties(prefix = "pedido360.mensajeria")
public class MensajeriaProperties {

    /** Region donde viven los recursos. */
    private String region = "us-east-1";

    /**
     * Si al arrancar se declara la topologia sobre AWS. Conviene dejarlo en
     * true: hace que recrear todo despues de un reinicio del laboratorio sea
     * levantar el servicio, en lugar de repetir la configuracion a mano.
     */
    private boolean declararAlIniciar = true;

    /**
     * Prefijo con el que se reconocen las colas y topicos del proyecto al
     * listarlos, para no mezclarlos con otros recursos de la cuenta.
     */
    private String prefijo = "pedido360-";

    private final Topico topico = new Topico();

    private List<Cola> colas = new ArrayList<>();

    private final Consumidores consumidores = new Consumidores();

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public boolean isDeclararAlIniciar() {
        return declararAlIniciar;
    }

    public void setDeclararAlIniciar(boolean declararAlIniciar) {
        this.declararAlIniciar = declararAlIniciar;
    }

    public String getPrefijo() {
        return prefijo;
    }

    public void setPrefijo(String prefijo) {
        this.prefijo = prefijo;
    }

    public Topico getTopico() {
        return topico;
    }

    public List<Cola> getColas() {
        return colas;
    }

    public void setColas(List<Cola> colas) {
        this.colas = colas;
    }

    public Consumidores getConsumidores() {
        return consumidores;
    }

    /** Busca una cola declarada por su identificador logico. */
    public Cola colaPorId(String id) {
        return colas.stream()
                .filter(cola -> cola.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "No hay una cola declarada con id '" + id + "'"));
    }

    public static class Topico {

        private String nombre;

        public String getNombre() {
            return nombre;
        }

        public void setNombre(String nombre) {
            this.nombre = nombre;
        }
    }

    /** Una cola, su DLQ, su retencion y su suscripcion al topico. */
    public static class Cola {

        /** Identificador logico, compartido con el id del consumidor. */
        private String id;

        private String nombre;

        /**
         * Cuanto vive un mensaje en la cola antes de que SQS lo borre solo.
         * Es el primer freno al crecimiento descontrolado.
         */
        private int retencionSegundos;

        /**
         * Cuanto tiempo queda invisible un mensaje tomado por un consumidor.
         * Tiene que ser mayor que lo que tarda en procesarse, o el mensaje se
         * entregaria a otro consumidor mientras el primero todavia trabaja.
         */
        private int visibilidadSegundos = 30;

        /**
         * Segundos de espera en cada consulta a la cola (long polling). Con 0
         * el consumidor pregunta en vacio todo el tiempo; con 20 espera a que
         * haya algo, lo que baja el costo y la latencia.
         */
        private int esperaLargaSegundos = 20;

        /**
         * Recepciones fallidas tras las cuales la Redrive Policy mueve el
         * mensaje a la DLQ. Es la red de seguridad: el consumidor deriva antes,
         * para poder adjuntar la causa.
         */
        private int maxReceiveCount = 5;

        /** Por que esta cola tiene la retencion que tiene. */
        private String justificacion;

        private final Dlq dlq = new Dlq();

        private final Suscripcion suscripcion = new Suscripcion();

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

        public int getRetencionSegundos() {
            return retencionSegundos;
        }

        public void setRetencionSegundos(int retencionSegundos) {
            this.retencionSegundos = retencionSegundos;
        }

        public int getVisibilidadSegundos() {
            return visibilidadSegundos;
        }

        public void setVisibilidadSegundos(int visibilidadSegundos) {
            this.visibilidadSegundos = visibilidadSegundos;
        }

        public int getEsperaLargaSegundos() {
            return esperaLargaSegundos;
        }

        public void setEsperaLargaSegundos(int esperaLargaSegundos) {
            this.esperaLargaSegundos = esperaLargaSegundos;
        }

        public int getMaxReceiveCount() {
            return maxReceiveCount;
        }

        public void setMaxReceiveCount(int maxReceiveCount) {
            this.maxReceiveCount = maxReceiveCount;
        }

        public String getJustificacion() {
            return justificacion;
        }

        public void setJustificacion(String justificacion) {
            this.justificacion = justificacion;
        }

        public Dlq getDlq() {
            return dlq;
        }

        public Suscripcion getSuscripcion() {
            return suscripcion;
        }
    }

    /** Cola de mensajes muertos asociada a una cola principal. */
    public static class Dlq {

        private String nombre;

        /**
         * La DLQ retiene mas tiempo que la cola principal a proposito: un
         * mensaje que fallo hay que alcanzar a diagnosticarlo, y eso puede
         * tomar dias.
         */
        private int retencionSegundos = 1_209_600;

        private String justificacion;

        public String getNombre() {
            return nombre;
        }

        public void setNombre(String nombre) {
            this.nombre = nombre;
        }

        public int getRetencionSegundos() {
            return retencionSegundos;
        }

        public void setRetencionSegundos(int retencionSegundos) {
            this.retencionSegundos = retencionSegundos;
        }

        public String getJustificacion() {
            return justificacion;
        }

        public void setJustificacion(String justificacion) {
            this.justificacion = justificacion;
        }
    }

    /** Suscripcion de una cola al topico, con su patron de enrutamiento. */
    public static class Suscripcion {

        /**
         * Entrega el cuerpo publicado tal cual. Sin esto, SNS envuelve el
         * mensaje en su propio JSON y el consumidor tendria que desenvolverlo.
         */
        private boolean rawMessageDelivery = true;

        /**
         * Filtro de suscripcion. Vacio significa fanout: la cola recibe todos
         * los eventos del topico. Con contenido significa entrega selectiva:
         * SNS solo entrega los eventos cuyos atributos coinciden.
         */
        private Map<String, List<String>> filtro = new LinkedHashMap<>();

        /** Que patron representa esta suscripcion, para documentarlo. */
        private String patron;

        public boolean isRawMessageDelivery() {
            return rawMessageDelivery;
        }

        public void setRawMessageDelivery(boolean rawMessageDelivery) {
            this.rawMessageDelivery = rawMessageDelivery;
        }

        public Map<String, List<String>> getFiltro() {
            return filtro;
        }

        public void setFiltro(Map<String, List<String>> filtro) {
            this.filtro = filtro;
        }

        public String getPatron() {
            return patron;
        }

        public void setPatron(String patron) {
            this.patron = patron;
        }

        public boolean tieneFiltro() {
            return filtro != null && !filtro.isEmpty();
        }
    }

    /**
     * Instancias del microservicio de consumidores a las que se les reenvian
     * las ordenes de pausa y reanudacion.
     */
    public static class Consumidores {

        /**
         * URL base de cada instancia. Son varias porque la demostracion de
         * disponibilidad levanta dos instancias sobre la misma cola, y pausar
         * un consumidor significa pausarlo en todas.
         */
        private List<String> instancias = new ArrayList<>();

        public List<String> getInstancias() {
            return instancias;
        }

        public void setInstancias(List<String> instancias) {
            this.instancias = instancias;
        }
    }
}
