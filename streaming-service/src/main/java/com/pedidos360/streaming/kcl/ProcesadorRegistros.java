package com.pedidos360.streaming.kcl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pedidos360.streaming.comun.CambioEstado;
import com.pedidos360.streaming.comun.ErrorNoRecuperableException;
import com.pedidos360.streaming.comun.ManejadorDominio;
import com.pedidos360.streaming.comun.RepositorioEstado;
import com.pedidos360.streaming.comun.ResultadoAplicacion;
import com.pedidos360.streaming.comun.SimuladorFallas;
import com.pedidos360.streaming.eventos.EventoFallido;
import com.pedidos360.streaming.eventos.EventoFallido.TipoFalla;
import com.pedidos360.streaming.kcl.ResultadoRegistro.Ruta;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;

/**
 * Politica de procesamiento y manejo de errores de un registro, en un solo
 * lugar y comun a todas las aplicaciones consumidoras.
 *
 * <p>Cada aplicacion aporta solo su {@link ManejadorDominio}. Las rutas
 * posibles son:
 * <ol>
 * <li><b>Ilegible</b>: el contenido no es JSON del contrato. Stream de fallidos,
 * sin reintentos.
 * <li><b>Invalido</b>: no cumple las validaciones del DTO. Stream de fallidos,
 * sin reintentos.
 * <li><b>Duplicado</b>: el idEvento ya se habia procesado. Se descarta.
 * <li><b>No recuperable</b>: el dominio lo rechaza. Stream de fallidos.
 * <li><b>Transitorio</b>: se reintenta con espera creciente; si se agotan los
 * intentos, stream de fallidos.
 * <li><b>Procesado</b>.
 * </ol>
 *
 * <p>Ninguna ruta lanza una excepcion hacia la KCL: todas terminan en un
 * resultado y el shard sigue avanzando. Eso es lo que garantiza que un evento
 * defectuoso no detenga el consumo.
 *
 * @param <T> tipo del evento de la aplicacion
 */
public class ProcesadorRegistros<T> {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorRegistros.class);

    private static final int VERSION_FALLIDO = 1;
    private static final int MAX_CARACTERES_CONTENIDO = 50_000;

    private final String aplicacion;
    private final ManejadorDominio<T> manejador;
    private final ObjectMapper objectMapper;
    private final Validator validador;
    private final RepositorioEstado repositorio;
    private final PublicadorFallidos publicadorFallidos;
    private final PoliticaReintentos reintentos;
    private final SimuladorFallas simuladorFallas;
    private final String instancia;

    public ProcesadorRegistros(String aplicacion, ManejadorDominio<T> manejador, ObjectMapper objectMapper,
            Validator validador, RepositorioEstado repositorio, PublicadorFallidos publicadorFallidos,
            PoliticaReintentos reintentos, SimuladorFallas simuladorFallas, String instancia) {
        this.aplicacion = aplicacion;
        this.manejador = manejador;
        this.objectMapper = objectMapper;
        this.validador = validador;
        this.repositorio = repositorio;
        this.publicadorFallidos = publicadorFallidos;
        this.reintentos = reintentos;
        this.simuladorFallas = simuladorFallas;
        this.instancia = instancia;
    }

    public String aplicacion() {
        return aplicacion;
    }

    public ResultadoRegistro procesar(RegistroEntrante registro) {
        // 1. El contenido tiene que ser legible. Si no lo es, reintentar es inutil.
        T evento;
        try {
            evento = objectMapper.readValue(registro.datos(), manejador.tipoEvento());
        } catch (Exception e) {
            return derivar(registro, null, TipoFalla.ILEGIBLE, "Contenido ilegible: " + resumen(e.getMessage()), 1);
        }
        if (evento == null) {
            return derivar(registro, null, TipoFalla.ILEGIBLE, "Contenido vacio", 1);
        }

        // 2. Debe cumplir el contrato del DTO.
        Set<ConstraintViolation<T>> violaciones = validador.validate(evento);
        String idEvento = idEventoSeguro(evento);
        if (!violaciones.isEmpty()) {
            String causa = "Evento invalido: " + violaciones.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            return derivar(registro, idEvento, TipoFalla.INVALIDO, causa, 1);
        }

        // 3. Procesamiento, con reintento de lo transitorio.
        int intento = 1;
        while (true) {
            try {
                simuladorFallas.aplicarSiCorresponde(aplicacion, intento);
                List<CambioEstado> cambios = manejador.procesar(evento);
                ResultadoAplicacion resultado = repositorio.aplicarUnaVez(aplicacion, idEvento, cambios);

                if (resultado == ResultadoAplicacion.DUPLICADO) {
                    log.info("[{}] Duplicado descartado | idEvento={} | intento={} | shard={} | seq={}",
                            aplicacion, idEvento, intento, registro.shardId(), registro.sequenceNumber());
                    return new ResultadoRegistro(Ruta.DUPLICADO, idEvento, intento,
                            "El idEvento ya se habia procesado; no se repite la accion");
                }

                log.info("[{}] Procesado | idEvento={} | intento={} | cambios={} | shard={} | seq={} | clave={}",
                        aplicacion, idEvento, intento, cambios.size(), registro.shardId(),
                        registro.sequenceNumber(), registro.partitionKey());
                return new ResultadoRegistro(Ruta.PROCESADO, idEvento, intento, "Procesado");

            } catch (ErrorNoRecuperableException e) {
                return derivar(registro, idEvento, TipoFalla.NO_RECUPERABLE, e.getMessage(), intento);

            } catch (RuntimeException e) {
                // Lo que no se declaro como no recuperable se trata como
                // transitorio: es mas seguro reintentar un error desconocido
                // que descartarlo al primer intento.
                if (!reintentos.quedanIntentos(intento)) {
                    String causa = "Reintentos agotados (%d de %d): %s"
                            .formatted(intento, reintentos.maxIntentos(), resumen(e.getMessage()));
                    return derivar(registro, idEvento, TipoFalla.REINTENTOS_AGOTADOS, causa, intento);
                }
                long esperaMs = reintentos.esperaTras(intento);
                log.warn("[{}] Error transitorio, se reintenta | idEvento={} | intento={} de {} | espera={} ms | causa={}",
                        aplicacion, idEvento, intento, reintentos.maxIntentos(), esperaMs, resumen(e.getMessage()));
                reintentos.esperar(intento);
                intento++;
            }
        }
    }

    private ResultadoRegistro derivar(RegistroEntrante registro, String idEvento, TipoFalla tipo, String causa,
            int intentos) {
        log.warn("[{}] Evento no procesable | idEvento={} | tipoFalla={} | intento={} | causa={} | shard={} | seq={}",
                aplicacion, idEvento, tipo, intentos, causa, registro.shardId(), registro.sequenceNumber());

        publicadorFallidos.publicar(new EventoFallido(
                VERSION_FALLIDO,
                idEvento,
                aplicacion,
                registro.stream(),
                registro.shardId(),
                registro.sequenceNumber(),
                registro.partitionKey(),
                tipo,
                causa,
                intentos,
                contenido(registro.datos()),
                instancia,
                Instant.now()));

        return new ResultadoRegistro(Ruta.DERIVADO_A_FALLIDOS, idEvento, intentos, tipo + ": " + causa);
    }

    /** El idEvento puede venir vacio en un evento invalido: no debe romper el log. */
    private String idEventoSeguro(T evento) {
        try {
            return manejador.idEvento(evento);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String contenido(byte[] datos) {
        String texto = new String(datos, StandardCharsets.UTF_8);
        return texto.length() > MAX_CARACTERES_CONTENIDO ? texto.substring(0, MAX_CARACTERES_CONTENIDO) : texto;
    }

    private static String resumen(String mensaje) {
        if (mensaje == null) {
            return "sin detalle";
        }
        String unaLinea = mensaje.replace('\n', ' ');
        return unaLinea.length() > 300 ? unaLinea.substring(0, 300) + "..." : unaLinea;
    }
}
