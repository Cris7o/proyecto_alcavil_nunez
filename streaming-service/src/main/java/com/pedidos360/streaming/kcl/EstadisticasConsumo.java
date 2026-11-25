package com.pedidos360.streaming.kcl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Lo que esta instancia esta consumiendo, por aplicacion y por shard.
 *
 * <p>Sirve para la demostracion de reparto: con dos instancias levantadas, cada
 * una muestra aqui solo los shards cuyo lease tiene. Si una se detiene, los
 * shards aparecen en la otra cuando vence el failover. La vista global (todas
 * las instancias) la arma el administrador leyendo la tabla de coordinacion.
 */
@Component
public class EstadisticasConsumo {

    private final Map<String, Map<String, Shard>> porAplicacion = new ConcurrentHashMap<>();

    public void shardAsignado(String aplicacion, String shardId, String checkpointInicial) {
        Shard shard = shard(aplicacion, shardId);
        shard.activo = true;
        shard.asignadoEn = Instant.now();
        shard.ultimoCheckpoint = checkpointInicial;
    }

    public void shardLiberado(String aplicacion, String shardId, String motivo) {
        Shard shard = shard(aplicacion, shardId);
        shard.activo = false;
        shard.motivoLiberacion = motivo;
    }

    public void registrar(String aplicacion, String shardId, ResultadoRegistro resultado, String sequenceNumber) {
        Shard shard = shard(aplicacion, shardId);
        switch (resultado.ruta()) {
            case PROCESADO -> shard.procesados.incrementAndGet();
            case DUPLICADO -> shard.duplicados.incrementAndGet();
            case DERIVADO_A_FALLIDOS -> shard.derivados.incrementAndGet();
        }
        if (resultado.intentos() > 1) {
            shard.reintentos.addAndGet(resultado.intentos() - 1L);
        }
        shard.ultimoSequenceNumber = sequenceNumber;
        shard.ultimoEventoEn = Instant.now();
    }

    public void retraso(String aplicacion, String shardId, Long millisBehindLatest) {
        if (millisBehindLatest != null) {
            shard(aplicacion, shardId).millisBehindLatest = millisBehindLatest;
        }
    }

    public void checkpoint(String aplicacion, String shardId, String sequenceNumber) {
        Shard shard = shard(aplicacion, shardId);
        shard.ultimoCheckpoint = sequenceNumber;
        shard.checkpointEn = Instant.now();
    }

    public List<VistaShard> vista(String aplicacion) {
        List<VistaShard> vista = new ArrayList<>();
        porAplicacion.getOrDefault(aplicacion, Map.of()).forEach((shardId, s) -> vista.add(new VistaShard(
                shardId, s.activo, s.asignadoEn, s.motivoLiberacion, s.procesados.get(), s.duplicados.get(),
                s.derivados.get(), s.reintentos.get(), s.ultimoSequenceNumber, s.ultimoCheckpoint,
                s.checkpointEn, s.millisBehindLatest, s.ultimoEventoEn)));
        vista.sort(Comparator.comparing(VistaShard::shardId));
        return vista;
    }

    private Shard shard(String aplicacion, String shardId) {
        return porAplicacion
                .computeIfAbsent(aplicacion, clave -> new ConcurrentHashMap<>())
                .computeIfAbsent(shardId, clave -> new Shard());
    }

    /** Contadores de un shard en esta instancia. */
    private static final class Shard {
        private volatile boolean activo;
        private volatile Instant asignadoEn;
        private volatile String motivoLiberacion;
        private final AtomicLong procesados = new AtomicLong();
        private final AtomicLong duplicados = new AtomicLong();
        private final AtomicLong derivados = new AtomicLong();
        private final AtomicLong reintentos = new AtomicLong();
        private volatile String ultimoSequenceNumber;
        private volatile String ultimoCheckpoint;
        private volatile Instant checkpointEn;
        private volatile Long millisBehindLatest;
        private volatile Instant ultimoEventoEn;
    }

    /** Foto de un shard para la API de control. */
    public record VistaShard(
            String shardId,
            boolean asignadoAEstaInstancia,
            Instant asignadoEn,
            String motivoLiberacion,
            long procesados,
            long duplicados,
            long derivadosAFallidos,
            long reintentos,
            String ultimoSequenceNumber,
            String ultimoCheckpoint,
            Instant checkpointEn,
            Long millisBehindLatest,
            Instant ultimoEventoEn) {
    }
}
