package com.pedidos360.streaming.kcl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.kinesis.exceptions.InvalidStateException;
import software.amazon.kinesis.exceptions.KinesisClientLibDependencyException;
import software.amazon.kinesis.exceptions.ShutdownException;
import software.amazon.kinesis.exceptions.ThrottlingException;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

/**
 * Procesador de un shard para una aplicacion consumidora. La KCL crea uno por
 * cada shard cuyo lease tiene esta instancia.
 *
 * <p>Es solo infraestructura: convierte cada registro de la KCL en un
 * {@link RegistroEntrante}, delega en {@link ProcesadorRegistros} (que no
 * lanza excepciones) y registra el avance con un checkpoint.
 *
 * <p><b>Checkpoint despues de procesar.</b> Se hace al final de cada lote,
 * cuando todos sus registros ya terminaron en una ruta definitiva (procesado,
 * duplicado o derivado a fallidos). Si la instancia muere a mitad de un lote,
 * otra toma el lease y relee desde el ultimo checkpoint: esos registros se
 * procesan de nuevo, y la idempotencia por idEvento hace que no se cuenten dos
 * veces. Es entrega "al menos una vez" con efecto "exactamente una vez".
 */
public class ProcesadorShard implements ShardRecordProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorShard.class);

    private static final int INTENTOS_CHECKPOINT = 3;
    private static final long ESPERA_CHECKPOINT_MS = 500;

    private final ProcesadorRegistros<?> procesador;
    private final EstadisticasConsumo estadisticas;
    private final String stream;
    private String shardId;

    public ProcesadorShard(ProcesadorRegistros<?> procesador, EstadisticasConsumo estadisticas, String stream) {
        this.procesador = procesador;
        this.estadisticas = estadisticas;
        this.stream = stream;
    }

    @Override
    public void initialize(InitializationInput entrada) {
        this.shardId = entrada.shardId();
        String desde = entrada.extendedSequenceNumber() == null
                ? "?"
                : entrada.extendedSequenceNumber().sequenceNumber();
        estadisticas.shardAsignado(procesador.aplicacion(), shardId, desde);
        log.info("[{}] Lease tomado | shard={} | stream={} | continua desde checkpoint={}",
                procesador.aplicacion(), shardId, stream, desde);
    }

    @Override
    public void processRecords(ProcessRecordsInput entrada) {
        List<KinesisClientRecord> registros = entrada.records();
        estadisticas.retraso(procesador.aplicacion(), shardId, entrada.millisBehindLatest());
        if (registros.isEmpty()) {
            return;
        }

        String ultimoSequence = null;
        for (KinesisClientRecord registro : registros) {
            RegistroEntrante entrante = aEntrante(registro);
            ResultadoRegistro resultado = procesador.procesar(entrante);
            estadisticas.registrar(procesador.aplicacion(), shardId, resultado, registro.sequenceNumber());
            ultimoSequence = registro.sequenceNumber();
        }

        checkpoint(entrada.checkpointer(), ultimoSequence, registros.size(), entrada.millisBehindLatest());
    }

    @Override
    public void leaseLost(LeaseLostInput entrada) {
        // Otra instancia tomo el shard. No se puede hacer checkpoint: el lease
        // ya no es de esta instancia.
        estadisticas.shardLiberado(procesador.aplicacion(), shardId, "lease tomado por otra instancia");
        log.warn("[{}] Lease perdido | shard={} | otra instancia continua desde el ultimo checkpoint",
                procesador.aplicacion(), shardId);
    }

    @Override
    public void shardEnded(ShardEndedInput entrada) {
        // El shard se cerro (resharding): hay que hacer checkpoint para que la
        // KCL empiece a leer los shards hijos.
        estadisticas.shardLiberado(procesador.aplicacion(), shardId, "shard cerrado por resharding");
        log.info("[{}] Shard cerrado (resharding) | shard={} | se pasa a los shards hijos",
                procesador.aplicacion(), shardId);
        checkpointFinal(entrada.checkpointer());
    }

    @Override
    public void shutdownRequested(ShutdownRequestedInput entrada) {
        // Apagado ordenado de la instancia: se deja el avance guardado para que
        // la siguiente instancia no relea nada.
        estadisticas.shardLiberado(procesador.aplicacion(), shardId, "instancia detenida");
        log.info("[{}] Apagado solicitado | shard={} | checkpoint final", procesador.aplicacion(), shardId);
        checkpointFinal(entrada.checkpointer());
    }

    private void checkpoint(RecordProcessorCheckpointer checkpointer, String sequence, int cantidad,
            Long retrasoMs) {
        for (int intento = 1; intento <= INTENTOS_CHECKPOINT; intento++) {
            try {
                checkpointer.checkpoint(sequence);
                estadisticas.checkpoint(procesador.aplicacion(), shardId, sequence);
                log.info("[{}] Checkpoint | shard={} | seq={} | lote={} | retraso={} ms",
                        procesador.aplicacion(), shardId, sequence, cantidad, retrasoMs);
                return;
            } catch (ShutdownException e) {
                log.warn("[{}] Checkpoint omitido: el lease del shard {} ya no es de esta instancia",
                        procesador.aplicacion(), shardId);
                return;
            } catch (ThrottlingException | KinesisClientLibDependencyException e) {
                log.warn("[{}] Checkpoint fallo (intento {} de {}) | shard={} | causa={}",
                        procesador.aplicacion(), intento, INTENTOS_CHECKPOINT, shardId, e.getMessage());
                dormir(ESPERA_CHECKPOINT_MS * intento);
            } catch (InvalidStateException e) {
                log.error("[{}] Checkpoint imposible: tabla de coordinacion en estado invalido | shard={}",
                        procesador.aplicacion(), shardId, e);
                return;
            }
        }
        // Sin checkpoint, el lote se relee si el shard cambia de instancia; la
        // idempotencia evita que se cuente dos veces.
        log.error("[{}] No se pudo hacer checkpoint del shard {} en seq={}",
                procesador.aplicacion(), shardId, sequence);
    }

    private void checkpointFinal(RecordProcessorCheckpointer checkpointer) {
        try {
            checkpointer.checkpoint();
        } catch (ShutdownException | InvalidStateException | ThrottlingException
                | KinesisClientLibDependencyException e) {
            log.warn("[{}] Checkpoint final del shard {} no se pudo hacer: {}",
                    procesador.aplicacion(), shardId, e.getMessage());
        }
    }

    private RegistroEntrante aEntrante(KinesisClientRecord registro) {
        byte[] datos = new byte[registro.data().remaining()];
        registro.data().duplicate().get(datos);
        return new RegistroEntrante(stream, shardId, registro.sequenceNumber(), registro.partitionKey(),
                registro.approximateArrivalTimestamp(), datos);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
