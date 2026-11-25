package com.pedidos360.backend.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion centralizada del streaming en el servicio de pedidos. Se
 * alimenta del bloque {@code pedido360.streaming} de
 * {@code application.properties}.
 *
 * <p>Ninguna clase de negocio conoce los nombres de los streams: solo depende
 * de {@link PublicadorStreamPedidos} y {@link PublicadorStreamActividad}.
 */
@ConfigurationProperties(prefix = "pedido360.streaming")
public class StreamingProperties {

    /** Permite apagar Kinesis en desarrollo local sin credenciales de AWS. */
    private boolean habilitado = true;
    private String region = "us-east-1";
    private final Streams streams = new Streams();
    private final Cliente cliente = new Cliente();
    private final Lote lote = new Lote();

    public boolean isHabilitado() {
        return habilitado;
    }

    public void setHabilitado(boolean habilitado) {
        this.habilitado = habilitado;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Streams getStreams() {
        return streams;
    }

    public Cliente getCliente() {
        return cliente;
    }

    public Lote getLote() {
        return lote;
    }

    public static class Streams {
        private String pedidos;
        private String actividad;

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
    }

    public static class Cliente {
        private int maxReintentosSdk = 3;
        private long timeoutLlamadaMs = 5000;
        private long timeoutIntentoMs = 2000;

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

    public static class Lote {
        private int maxIntentos = 3;
        private long esperaBaseMs = 100;
        private int maxEventosPorPeticion = 100;

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

        public int getMaxEventosPorPeticion() {
            return maxEventosPorPeticion;
        }

        public void setMaxEventosPorPeticion(int maxEventosPorPeticion) {
            this.maxEventosPorPeticion = maxEventosPorPeticion;
        }
    }
}
