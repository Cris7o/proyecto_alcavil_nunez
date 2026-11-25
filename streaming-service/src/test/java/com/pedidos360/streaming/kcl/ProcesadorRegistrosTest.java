package com.pedidos360.streaming.kcl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pedidos360.streaming.Eventos;
import com.pedidos360.streaming.RepositorioEnMemoria;
import com.pedidos360.streaming.comun.CalendarioNegocio;
import com.pedidos360.streaming.comun.ModoFalla;
import com.pedidos360.streaming.comun.SimuladorFallas;
import com.pedidos360.streaming.eventos.EventoFallido;
import com.pedidos360.streaming.eventos.EventoFallido.TipoFalla;
import com.pedidos360.streaming.eventos.EventoPedidoStream;
import com.pedidos360.streaming.kcl.ResultadoRegistro.Ruta;
import com.pedidos360.streaming.ventas.TableroVentasManejador;

import jakarta.validation.Validation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las rutas del manejo de errores, sin Kinesis ni DynamoDB: cada registro
 * termina en una ruta definida y ninguna lanza excepcion hacia la KCL.
 */
class ProcesadorRegistrosTest {

    private static final String APP = "pedido360-tablero-ventas";
    private static final int MAX_INTENTOS = 4;

    private final ObjectMapper json = JsonMapper.builder().build();
    private RepositorioEnMemoria repositorio;
    private List<EventoFallido> fallidos;
    private List<Long> esperas;
    private SimuladorFallas simulador;
    private ProcesadorRegistros<EventoPedidoStream> procesador;

    @BeforeEach
    void preparar() {
        repositorio = new RepositorioEnMemoria();
        fallidos = new ArrayList<>();
        esperas = new ArrayList<>();
        simulador = new SimuladorFallas();

        CalendarioNegocio calendario = new CalendarioNegocio(ZoneId.of("America/Santiago"),
                Clock.fixed(Instant.parse("2026-11-24T19:00:00Z"), ZoneOffset.UTC));
        PoliticaReintentos reintentos = new PoliticaReintentos(MAX_INTENTOS, 200, 3000, esperas::add);

        procesador = new ProcesadorRegistros<>(APP, new TableroVentasManejador(calendario), json,
                Validation.buildDefaultValidatorFactory().getValidator(), repositorio,
                fallido -> fallidos.add(fallido), reintentos, simulador, "test");
    }

    @Test
    void eventoValidoSeProcesaEnElPrimerIntento() {
        ResultadoRegistro resultado = procesador.procesar(registro(Eventos.pedidoCreado(101)));

        assertThat(resultado.ruta()).isEqualTo(Ruta.PROCESADO);
        assertThat(resultado.intentos()).isEqualTo(1);
        assertThat(fallidos).isEmpty();
    }

    @Test
    void elMismoEventoLeidoDosVecesSeCuentaUnaSola() {
        EventoPedidoStream evento = Eventos.pedidoCreado(102);

        ResultadoRegistro primero = procesador.procesar(registro(evento));
        ResultadoRegistro segundo = procesador.procesar(registro(evento));

        assertThat(primero.ruta()).isEqualTo(Ruta.PROCESADO);
        assertThat(segundo.ruta()).isEqualTo(Ruta.DUPLICADO);
        assertThat(repositorio.leerItem("VENTAS#2026-11-24", "RESUMEN").orElseThrow().get("pedidosCreados"))
                .isEqualTo(BigDecimal.ONE);
    }

    @Test
    void contenidoQueNoEsJsonVaAFallidosSinReintentar() {
        ResultadoRegistro resultado = procesador.procesar(registro("esto no es json".getBytes(StandardCharsets.UTF_8)));

        assertThat(resultado.ruta()).isEqualTo(Ruta.DERIVADO_A_FALLIDOS);
        assertThat(fallidos).singleElement().satisfies(f -> {
            assertThat(f.tipoFalla()).isEqualTo(TipoFalla.ILEGIBLE);
            assertThat(f.intentos()).isEqualTo(1);
            assertThat(f.contenidoOriginal()).isEqualTo("esto no es json");
            assertThat(f.shardId()).isEqualTo("shardId-000000000001");
        });
        assertThat(esperas).isEmpty();
    }

    @Test
    void eventoQueNoCumpleElContratoVaAFallidosConLosCamposInvalidos() {
        String sinIdYConTotalNegativo = """
                {"version":1,"tipo":"PEDIDO_CREADO","pedidoId":5,"estadoNuevo":"PENDIENTE",
                 "clienteId":7,"total":-10,"cantidadItems":1,"ocurridoEn":"2026-11-24T18:30:00Z"}
                """;

        ResultadoRegistro resultado = procesador.procesar(registro(sinIdYConTotalNegativo.getBytes(StandardCharsets.UTF_8)));

        assertThat(resultado.ruta()).isEqualTo(Ruta.DERIVADO_A_FALLIDOS);
        assertThat(fallidos).singleElement().satisfies(f -> {
            assertThat(f.tipoFalla()).isEqualTo(TipoFalla.INVALIDO);
            assertThat(f.causa()).contains("idEvento").contains("total");
        });
    }

    @Test
    void reglaDeNegocioIncumplidaVaAFallidosSinReintentar() {
        EventoPedidoStream valido = Eventos.pedidoCreado(103);
        EventoPedidoStream totalQueNoCuadra = new EventoPedidoStream(1, valido.idEvento(), valido.tipo(),
                valido.pedidoId(), null, "PENDIENTE", 7L, new BigDecimal("1"), 3, valido.items(),
                valido.ocurridoEn(), "test");

        ResultadoRegistro resultado = procesador.procesar(registro(totalQueNoCuadra));

        assertThat(resultado.ruta()).isEqualTo(Ruta.DERIVADO_A_FALLIDOS);
        assertThat(fallidos.get(0).tipoFalla()).isEqualTo(TipoFalla.NO_RECUPERABLE);
        assertThat(fallidos.get(0).causa()).contains("no cuadra");
        assertThat(esperas).isEmpty();
    }

    @Test
    void errorTransitorioSeReintentaConEsperaCrecienteYTerminaBien() {
        repositorio.fallarProximas(2);

        ResultadoRegistro resultado = procesador.procesar(registro(Eventos.pedidoCreado(104)));

        assertThat(resultado.ruta()).isEqualTo(Ruta.PROCESADO);
        assertThat(resultado.intentos()).isEqualTo(3);
        assertThat(esperas).hasSize(2);
        assertThat(esperas.get(1)).isGreaterThan(esperas.get(0));
        assertThat(fallidos).isEmpty();
    }

    @Test
    void errorTransitorioPersistenteAgotaLosIntentosYVaAFallidos() {
        simulador.establecer(APP, ModoFalla.TRANSITORIA_PERSISTENTE);

        ResultadoRegistro resultado = procesador.procesar(registro(Eventos.pedidoCreado(105)));

        assertThat(resultado.ruta()).isEqualTo(Ruta.DERIVADO_A_FALLIDOS);
        assertThat(resultado.intentos()).isEqualTo(MAX_INTENTOS);
        assertThat(esperas).hasSize(MAX_INTENTOS - 1);
        assertThat(fallidos).singleElement().satisfies(f -> {
            assertThat(f.tipoFalla()).isEqualTo(TipoFalla.REINTENTOS_AGOTADOS);
            assertThat(f.intentos()).isEqualTo(MAX_INTENTOS);
            assertThat(f.idEvento()).isNotBlank();
        });
    }

    @Test
    void unEventoDefectuosoNoDetieneLosSiguientesDelShard() {
        List<ResultadoRegistro> resultados = List.of(
                procesador.procesar(registro(Eventos.pedidoCreado(106))),
                procesador.procesar(registro("{roto".getBytes(StandardCharsets.UTF_8))),
                procesador.procesar(registro(Eventos.pedidoCreado(107))));

        assertThat(resultados).extracting(ResultadoRegistro::ruta)
                .containsExactly(Ruta.PROCESADO, Ruta.DERIVADO_A_FALLIDOS, Ruta.PROCESADO);
    }

    @Test
    void siNoSePuedePublicarEnFallidosElShardIgualSigue() {
        ProcesadorRegistros<EventoPedidoStream> conFallidosCaido = new ProcesadorRegistros<>(APP,
                new TableroVentasManejador(new CalendarioNegocio("America/Santiago")), json,
                Validation.buildDefaultValidatorFactory().getValidator(), repositorio, fallido -> false,
                new PoliticaReintentos(1, 1, 1, ms -> { }), simulador, "test");

        ResultadoRegistro resultado = conFallidosCaido.procesar(registro("x".getBytes(StandardCharsets.UTF_8)));

        assertThat(resultado.ruta()).isEqualTo(Ruta.DERIVADO_A_FALLIDOS);
    }

    private RegistroEntrante registro(EventoPedidoStream evento) {
        return registro(json.writeValueAsBytes(evento));
    }

    private RegistroEntrante registro(byte[] datos) {
        return new RegistroEntrante("pedido360-pedidos", "shardId-000000000001", "49650000000000000001",
                "101", Instant.now(), datos);
    }
}
