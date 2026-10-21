package com.pedidos360.mensajeria.comun;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import com.pedidos360.mensajeria.config.MensajeriaProperties.Cola;

import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pruebas de la politica de confirmacion y manejo de errores.
 *
 * <p>Cubren las cinco rutas que puede tomar un mensaje, que es lo que de verdad
 * determina si se pierden mensajes o si se reprocesan de mas.
 */
class ProcesadorEventosTest {

    private static final String JSON_VALIDO = """
            {
              "idEvento": "evt-1",
              "tipo": "PEDIDO_CREADO",
              "pedidoId": 7,
              "estadoAnterior": null,
              "estadoNuevo": "PENDIENTE",
              "clienteId": 3,
              "clienteNombre": "Ana",
              "clienteEmail": "ana@pedido360.cl",
              "total": 15990,
              "ocurridoEn": "2026-10-19T12:00:00Z"
            }""";

    private ProcesadorEventos procesador;
    private RegistroEventosProcesados registroProcesados;
    private DlqEspia dlq;
    private ConfirmacionEspia confirmacion;
    private Cola cola;

    @BeforeEach
    void prepararEscenario() {
        registroProcesados = new RegistroEventosProcesados();
        dlq = new DlqEspia();
        confirmacion = new ConfirmacionEspia();

        procesador = new ProcesadorEventos(
                JsonMapper.builder().build(),
                registroProcesados,
                dlq,
                new RegistroActividad("prueba"),
                new SimuladorFallas());

        cola = new Cola();
        cola.setId("notificaciones");
        cola.setNombre("pedido360-notificaciones");
        cola.setDlq("pedido360-notificaciones-dlq");
        cola.setMaxReintentos(3);
    }

    @Test
    @DisplayName("Un evento procesado sin errores se confirma una sola vez")
    void confirmaCuandoElProcesamientoFunciona() {
        AtomicInteger ejecuciones = new AtomicInteger();

        procesador.procesar(cola, mensaje(JSON_VALIDO, 1), confirmacion,
                evento -> ejecuciones.incrementAndGet());

        assertThat(ejecuciones).hasValue(1);
        assertThat(confirmacion.confirmaciones).isEqualTo(1);
        assertThat(dlq.derivados).isEmpty();
        assertThat(registroProcesados.yaFueProcesado("notificaciones", "evt-1")).isTrue();
    }

    @Test
    @DisplayName("Un evento repetido se confirma sin volver a ejecutar la accion")
    void descartaDuplicadosSinReprocesar() {
        AtomicInteger ejecuciones = new AtomicInteger();

        procesador.procesar(cola, mensaje(JSON_VALIDO, 1), confirmacion,
                evento -> ejecuciones.incrementAndGet());
        procesador.procesar(cola, mensaje(JSON_VALIDO, 1), confirmacion,
                evento -> ejecuciones.incrementAndGet());

        // La accion corrio una sola vez, pero los dos mensajes se confirmaron:
        // dejar el duplicado sin confirmar lo haria volver para siempre.
        assertThat(ejecuciones).hasValue(1);
        assertThat(confirmacion.confirmaciones).isEqualTo(2);
        assertThat(dlq.derivados).isEmpty();
    }

    @Test
    @DisplayName("El mismo evento en otra cola no es un duplicado: el fanout lo entrega a las tres")
    void elMismoEventoEnOtraColaSeProcesa() {
        AtomicInteger ejecuciones = new AtomicInteger();
        Cola auditoria = new Cola();
        auditoria.setId("auditoria");
        auditoria.setNombre("pedido360-auditoria");
        auditoria.setDlq("pedido360-auditoria-dlq");
        auditoria.setMaxReintentos(3);

        procesador.procesar(cola, mensaje(JSON_VALIDO, 1), confirmacion,
                evento -> ejecuciones.incrementAndGet());
        procesador.procesar(auditoria, mensaje(JSON_VALIDO, 1), confirmacion,
                evento -> ejecuciones.incrementAndGet());

        assertThat(ejecuciones).hasValue(2);
        assertThat(confirmacion.confirmaciones).isEqualTo(2);
    }

    @Test
    @DisplayName("Un error no recuperable va a la DLQ en el primer intento")
    void derivaLoNoRecuperableSinGastarReintentos() {
        procesador.procesar(cola, mensaje(JSON_VALIDO, 1), confirmacion, evento -> {
            throw new ErrorNoRecuperableException("falta el correo del cliente");
        });

        assertThat(dlq.derivados).hasSize(1);
        assertThat(dlq.derivados.get(0).idEvento()).isEqualTo("evt-1");
        assertThat(dlq.derivados.get(0).intento()).isEqualTo(1);
        assertThat(dlq.derivados.get(0).causa()).contains("falta el correo del cliente");
        assertThat(confirmacion.confirmaciones).isEqualTo(1);
    }

    @Test
    @DisplayName("Un error recuperable con intentos disponibles no se confirma, para que SQS lo reentregue")
    void noConfirmaLoRecuperableMientrasQuedenIntentos() {
        procesador.procesar(cola, mensaje(JSON_VALIDO, 2), confirmacion, evento -> {
            throw new ErrorRecuperableException("el proveedor de correo no responde");
        });

        // Sin confirmar y sin derivar: el mensaje vuelve solo al expirar el
        // visibility timeout. Confirmarlo aqui lo perderia.
        assertThat(confirmacion.confirmaciones).isZero();
        assertThat(dlq.derivados).isEmpty();
    }

    @Test
    @DisplayName("Un error recuperable con los reintentos agotados va a la DLQ con la causa")
    void derivaLoRecuperableCuandoSeAgotanLosReintentos() {
        procesador.procesar(cola, mensaje(JSON_VALIDO, 3), confirmacion, evento -> {
            throw new ErrorRecuperableException("el proveedor de correo no responde");
        });

        assertThat(dlq.derivados).hasSize(1);
        assertThat(dlq.derivados.get(0).intento()).isEqualTo(3);
        assertThat(dlq.derivados.get(0).causa()).contains("Reintentos agotados");
        assertThat(confirmacion.confirmaciones).isEqualTo(1);
    }

    @Test
    @DisplayName("Un cuerpo ilegible va a la DLQ sin reintentarse")
    void derivaLoQueNoSePuedeLeer() {
        procesador.procesar(cola, mensaje("{ esto no es json", 1), confirmacion,
                evento -> {
                    throw new AssertionError("no deberia ejecutarse la accion de dominio");
                });

        assertThat(dlq.derivados).hasSize(1);
        assertThat(dlq.derivados.get(0).causa()).contains("Cuerpo ilegible");
        assertThat(confirmacion.confirmaciones).isEqualTo(1);
    }

    @Test
    @DisplayName("Un evento sin idEvento ni tipo va a la DLQ: no se puede deduplicar ni enrutar")
    void derivaLosEventosIncompletos() {
        procesador.procesar(cola, mensaje("{\"pedidoId\": 7}", 1), confirmacion,
                evento -> {
                    throw new AssertionError("no deberia ejecutarse la accion de dominio");
                });

        assertThat(dlq.derivados).hasSize(1);
        assertThat(dlq.derivados.get(0).causa()).contains("Evento incompleto");
        assertThat(confirmacion.confirmaciones).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Dobles de prueba
    // ------------------------------------------------------------------

    private Message<String> mensaje(String cuerpo, int numeroDeIntento) {
        return MessageBuilder.withPayload(cuerpo)
                .setHeader(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT,
                        String.valueOf(numeroDeIntento))
                .build();
    }

    /** Registra las derivaciones en lugar de escribir en SQS. */
    private static class DlqEspia extends RegistroMensajesNoEntregados {

        private final List<Derivacion> derivados = new ArrayList<>();

        DlqEspia() {
            super(null);
        }

        @Override
        public void derivar(String nombreDlq, String colaOrigen, String idEvento, int intento,
                String causa, String cuerpoOriginal) {
            derivados.add(new Derivacion(nombreDlq, colaOrigen, idEvento, intento, causa));
        }

        record Derivacion(String nombreDlq, String colaOrigen, String idEvento, int intento,
                String causa) {
        }
    }

    /** Cuenta las confirmaciones explicitas. */
    private static class ConfirmacionEspia implements Acknowledgement {

        private int confirmaciones;

        @Override
        public void acknowledge() {
            confirmaciones++;
        }

        @Override
        public CompletableFuture<Void> acknowledgeAsync() {
            acknowledge();
            return CompletableFuture.completedFuture(null);
        }
    }
}
