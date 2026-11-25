package com.pedidos360.admin.config;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Declaracion de la plataforma de streaming. Se alimenta del bloque
 * {@code pedido360.streaming} de {@code application.yml}, que es la fuente de
 * verdad: nombres, shards, retencion, modo de capacidad, aplicaciones
 * consumidoras, tabla de estado y alarma.
 */
@ConfigurationProperties(prefix = "pedido360.streaming")
public class StreamingProperties {

    private String prefijo = "pedido360-";
    private boolean declararAlIniciar;
    private List<Stream> streams = new ArrayList<>();
    private List<Aplicacion> aplicaciones = new ArrayList<>();
    private final Consumidores consumidores = new Consumidores();
    private final TablaEstado tablaEstado = new TablaEstado();
    private final Alarma alarma = new Alarma();

    public Stream stream(String id) {
        return streams.stream()
                .filter(stream -> stream.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Stream no declarado: " + id));
    }

    public Optional<Stream> streamPorNombre(String nombre) {
        return streams.stream().filter(stream -> stream.getNombre().equals(nombre)).findFirst();
    }

    public Aplicacion aplicacion(String nombre) {
        return aplicaciones.stream()
                .filter(aplicacion -> aplicacion.getNombre().equals(nombre))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Aplicacion consumidora no declarada: " + nombre));
    }

    public String getPrefijo() {
        return prefijo;
    }

    public void setPrefijo(String prefijo) {
        this.prefijo = prefijo;
    }

    public boolean isDeclararAlIniciar() {
        return declararAlIniciar;
    }

    public void setDeclararAlIniciar(boolean declararAlIniciar) {
        this.declararAlIniciar = declararAlIniciar;
    }

    public List<Stream> getStreams() {
        return streams;
    }

    public void setStreams(List<Stream> streams) {
        this.streams = streams;
    }

    public List<Aplicacion> getAplicaciones() {
        return aplicaciones;
    }

    public void setAplicaciones(List<Aplicacion> aplicaciones) {
        this.aplicaciones = aplicaciones;
    }

    public Consumidores getConsumidores() {
        return consumidores;
    }

    public TablaEstado getTablaEstado() {
        return tablaEstado;
    }

    public Alarma getAlarma() {
        return alarma;
    }

    /** Un stream declarado, con sus parametros de administracion justificados. */
    public static class Stream {
        private String id;
        private String nombre;
        private String modoCapacidad = "PROVISIONED";
        private int shards = 1;
        private int retencionHoras = 24;
        private String claveParticion;
        private List<String> metricasPorShard = new ArrayList<>();
        private String justificacion;

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

        public String getModoCapacidad() {
            return modoCapacidad;
        }

        public void setModoCapacidad(String modoCapacidad) {
            this.modoCapacidad = modoCapacidad;
        }

        public int getShards() {
            return shards;
        }

        public void setShards(int shards) {
            this.shards = shards;
        }

        public int getRetencionHoras() {
            return retencionHoras;
        }

        public void setRetencionHoras(int retencionHoras) {
            this.retencionHoras = retencionHoras;
        }

        public String getClaveParticion() {
            return claveParticion;
        }

        public void setClaveParticion(String claveParticion) {
            this.claveParticion = claveParticion;
        }

        public List<String> getMetricasPorShard() {
            return metricasPorShard;
        }

        public void setMetricasPorShard(List<String> metricasPorShard) {
            this.metricasPorShard = metricasPorShard;
        }

        public String getJustificacion() {
            return justificacion;
        }

        public void setJustificacion(String justificacion) {
            this.justificacion = justificacion;
        }
    }

    /** Aplicacion consumidora: su nombre es tambien el de su tabla de coordinacion. */
    public static class Aplicacion {
        private String nombre;
        private String stream;
        private String justificacion;

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

        public String getJustificacion() {
            return justificacion;
        }

        public void setJustificacion(String justificacion) {
            this.justificacion = justificacion;
        }
    }

    public static class Consumidores {
        private List<String> instancias = new ArrayList<>();

        public List<String> getInstancias() {
            return instancias;
        }

        public void setInstancias(List<String> instancias) {
            this.instancias = instancias;
        }
    }

    public static class TablaEstado {
        private String nombre;
        private String atributoTtl = "expiraEn";

        public String getNombre() {
            return nombre;
        }

        public void setNombre(String nombre) {
            this.nombre = nombre;
        }

        public String getAtributoTtl() {
            return atributoTtl;
        }

        public void setAtributoTtl(String atributoTtl) {
            this.atributoTtl = atributoTtl;
        }
    }

    public static class Alarma {
        private String nombre;
        private String stream;
        private String metrica = "GetRecords.IteratorAgeMilliseconds";
        private long umbralMs = 60000;
        private int periodoSegundos = 60;
        private int periodosEvaluacion = 3;
        private int datapointsParaAlarma = 2;
        private String sinDatos = "breaching";

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

        public String getMetrica() {
            return metrica;
        }

        public void setMetrica(String metrica) {
            this.metrica = metrica;
        }

        public long getUmbralMs() {
            return umbralMs;
        }

        public void setUmbralMs(long umbralMs) {
            this.umbralMs = umbralMs;
        }

        public int getPeriodoSegundos() {
            return periodoSegundos;
        }

        public void setPeriodoSegundos(int periodoSegundos) {
            this.periodoSegundos = periodoSegundos;
        }

        public int getPeriodosEvaluacion() {
            return periodosEvaluacion;
        }

        public void setPeriodosEvaluacion(int periodosEvaluacion) {
            this.periodosEvaluacion = periodosEvaluacion;
        }

        public int getDatapointsParaAlarma() {
            return datapointsParaAlarma;
        }

        public void setDatapointsParaAlarma(int datapointsParaAlarma) {
            this.datapointsParaAlarma = datapointsParaAlarma;
        }

        public String getSinDatos() {
            return sinDatos;
        }

        public void setSinDatos(String sinDatos) {
            this.sinDatos = sinDatos;
        }
    }
}
