package com.pedidos360.streaming.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion centralizada del streaming. Se alimenta del bloque
 * {@code pedido360.streaming} de {@code application.yml}.
 *
 * <p>Es el unico lugar del microservicio donde aparecen la region, los nombres
 * de los streams, los nombres de las aplicaciones consumidoras y los parametros
 * del cliente. Las clases de dominio no conocen ninguno de estos valores.
 */
@ConfigurationProperties(prefix = "pedido360.streaming")
public class StreamingProperties {

    private String region = "us-east-1";
    private String zonaHoraria = "America/Santiago";
    private String tablaEstado;
    private final Streams streams = new Streams();
    private final Cliente cliente = new Cliente();
    private final Lectura lectura = new Lectura();
    private final Errores errores = new Errores();
    private final Cocina cocina = new Cocina();
    private List<Aplicacion> aplicaciones = new ArrayList<>();

    /** Nombre real del stream a partir de su id logico ({@code pedidos}, {@code actividad}). */
    public String nombreStream(String idStream) {
        return switch (idStream) {
            case "pedidos" -> streams.getPedidos();
            case "actividad" -> streams.getActividad();
            case "fallidos" -> streams.getFallidos();
            default -> throw new IllegalArgumentException("Stream no declarado: " + idStream);
        };
    }

    public Aplicacion aplicacion(String nombre) {
        return aplicaciones.stream()
                .filter(aplicacion -> aplicacion.getNombre().equals(nombre))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Aplicacion consumidora no declarada: " + nombre));
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getZonaHoraria() {
        return zonaHoraria;
    }

    public void setZonaHoraria(String zonaHoraria) {
        this.zonaHoraria = zonaHoraria;
    }

    public String getTablaEstado() {
        return tablaEstado;
    }

    public void setTablaEstado(String tablaEstado) {
        this.tablaEstado = tablaEstado;
    }

    public Streams getStreams() {
        return streams;
    }

    public Cliente getCliente() {
        return cliente;
    }

    public Lectura getLectura() {
        return lectura;
    }

    public Errores getErrores() {
        return errores;
    }

    public Cocina getCocina() {
        return cocina;
    }

    public List<Aplicacion> getAplicaciones() {
        return aplicaciones;
    }

    public void setAplicaciones(List<Aplicacion> aplicaciones) {
        this.aplicaciones = aplicaciones;
    }

    /** Nombres de los streams, por id logico. */
    public static class Streams {
        private String pedidos;
        private String actividad;
        private String fallidos;

        public String getPedidos() {
            return pedidos;
        }

        public void setPedidos(String pedidos) {
            this.pedidos = pedidos;
        }

        public String getActividad() {
            return actividad;
        }

        public void setActividad(String actividad) {
            this.actividad = actividad;
        }

        public String getFallidos() {
            return fallidos;
        }

        public void setFallidos(String fallidos) {
            this.fallidos = fallidos;
        }
    }

    /** Reintentos y timeouts del SDK. */
    public static class Cliente {
        private int maxReintentosSdk = 5;
        private long timeoutLlamadaMs = 10000;
        private long timeoutIntentoMs = 3000;

        public int getMaxReintentosSdk() {
            return maxReintentosSdk;
        }

        public void setMaxReintentosSdk(int maxReintentosSdk) {
            this.maxReintentosSdk = maxReintentosSdk;
        }

        public long getTimeoutLlamadaMs() {
            return timeoutLlamadaMs;
        }

        public void setTimeoutLlamadaMs(long timeoutLlamadaMs) {
            this.timeoutLlamadaMs = timeoutLlamadaMs;
        }

        public long getTimeoutIntentoMs() {
            return timeoutIntentoMs;
        }

        public void setTimeoutIntentoMs(long timeoutIntentoMs) {
            this.timeoutIntentoMs = timeoutIntentoMs;
        }
    }

    /** Parametros de lectura de la KCL. */
    public static class Lectura {
        private int maxRegistros = 500;
        private long pausaEntreLecturasMs = 1000;
        private long failoverMs = 10000;
        private long sincronizacionShardsMs = 60000;

        public int getMaxRegistros() {
            return maxRegistros;
        }

        public void setMaxRegistros(int maxRegistros) {
            this.maxRegistros = maxRegistros;
        }

        public long getPausaEntreLecturasMs() {
            return pausaEntreLecturasMs;
        }

        public void setPausaEntreLecturasMs(long pausaEntreLecturasMs) {
            this.pausaEntreLecturasMs = pausaEntreLecturasMs;
        }

        public long getFailoverMs() {
            return failoverMs;
        }

        public void setFailoverMs(long failoverMs) {
            this.failoverMs = failoverMs;
        }

        public long getSincronizacionShardsMs() {
            return sincronizacionShardsMs;
        }

        public void setSincronizacionShardsMs(long sincronizacionShardsMs) {
            this.sincronizacionShardsMs = sincronizacionShardsMs;
        }
    }

    /** Politica de reintentos y de duplicados. */
    public static class Errores {
        private int maxIntentos = 4;
        private long esperaBaseMs = 200;
        private long esperaMaximaMs = 3000;
        private long recordarProcesadosHoras = 192;

        public int getMaxIntentos() {
            return maxIntentos;
        }

        public void setMaxIntentos(int maxIntentos) {
            this.maxIntentos = maxIntentos;
        }

        public long getEsperaBaseMs() {
            return esperaBaseMs;
        }

        public void setEsperaBaseMs(long esperaBaseMs) {
            this.esperaBaseMs = esperaBaseMs;
        }

        public long getEsperaMaximaMs() {
            return esperaMaximaMs;
        }

        public void setEsperaMaximaMs(long esperaMaximaMs) {
            this.esperaMaximaMs = esperaMaximaMs;
        }

        public long getRecordarProcesadosHoras() {
            return recordarProcesadosHoras;
        }

        public void setRecordarProcesadosHoras(long recordarProcesadosHoras) {
            this.recordarProcesadosHoras = recordarProcesadosHoras;
        }
    }

    /** Umbrales del seguimiento de cocina. */
    public static class Cocina {
        private Map<EstadoPedidoClave, Integer> minutosMaximos = new EnumMap<>(EstadoPedidoClave.class);

        public Map<EstadoPedidoClave, Integer> getMinutosMaximos() {
            return minutosMaximos;
        }

        public void setMinutosMaximos(Map<EstadoPedidoClave, Integer> minutosMaximos) {
            this.minutosMaximos = minutosMaximos;
        }
    }

    /** Estados del pedido que tienen un tiempo maximo de permanencia. */
    public enum EstadoPedidoClave {
        PENDIENTE, EN_PREPARACION, LISTO, EN_REPARTO
    }

    /** Una aplicacion consumidora (consumer group de Kinesis). */
    public static class Aplicacion {
        /** Nombre de la aplicacion. Es tambien el nombre de su tabla de coordinacion. */
        private String nombre;
        /** Id logico del stream que lee. */
        private String stream;
        /** Dominio funcional que procesa sus eventos. */
        private String dominio;
        /** TRIM_HORIZON o LATEST: desde donde lee la primera vez. */
        private String posicionInicial = "TRIM_HORIZON";
        /** Permite levantar una instancia que solo ejecuta algunas aplicaciones. */
        private boolean activa = true;

        public String getNombre() {
            return nombre;
        }

        public void setNombre(String nombre) {
            this.nombre = nombre;
        }

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = stream;
        }

        public String getDominio() {
            return dominio;
        }

        public void setDominio(String dominio) {
            this.dominio = dominio;
        }

        public String getPosicionInicial() {
            return posicionInicial;
        }

        public void setPosicionInicial(String posicionInicial) {
            this.posicionInicial = posicionInicial;
        }

        public boolean isActiva() {
            return activa;
        }

        public void setActiva(boolean activa) {
            this.activa = activa;
        }
    }
}
