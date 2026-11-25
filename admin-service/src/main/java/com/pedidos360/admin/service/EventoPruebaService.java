package com.pedidos360.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import com.pedidos360.admin.config.StreamingProperties;
import com.pedidos360.admin.dto.StreamDto;
import com.pedidos360.admin.dto.StreamDto.VariantePrueba;

import tools.jackson.databind.ObjectMapper;

/**
 * Publica eventos de prueba en el stream de pedidos para demostrar en vivo cada
 * ruta del manejo de errores de los consumidores, sin tocar datos reales.
 *
 * <p>Usa {@link StreamAdminService} para publicar: no llama al SDK por su
 * cuenta.
 */
@Service
public class EventoPruebaService {

    /** Los pedidos de prueba usan ids altos para no confundirse con los reales. */
    private static final long BASE_PEDIDO_PRUEBA = 900_000;

    private final StreamAdminService streamAdmin;
    private final StreamingProperties propiedades;
    private final ObjectMapper objectMapper;

    public EventoPruebaService(StreamAdminService streamAdmin, StreamingProperties propiedades,
            ObjectMapper objectMapper) {
        this.streamAdmin = streamAdmin;
        this.propiedades = propiedades;
        this.objectMapper = objectMapper;
    }

    public StreamDto.ResultadoPrueba publicar(VariantePrueba variante) {
        String stream = propiedades.stream("pedidos").getNombre();
        long pedidoId = BASE_PEDIDO_PRUEBA + ThreadLocalRandom.current().nextInt(1, 99_999);
        String idEvento = UUID.randomUUID().toString();
        String clave = String.valueOf(pedidoId);

        return switch (variante) {
            case VALIDO -> new StreamDto.ResultadoPrueba(stream, variante,
                    "Las dos aplicaciones del stream lo procesan: suma una venta al tablero y aparece en cocina.",
                    List.of(streamAdmin.publicarCrudo(stream, idEvento, clave,
                            json(pedidoCreado(idEvento, pedidoId, 14470)))));

            case DUPLICADO -> {
                String contenido = json(pedidoCreado(idEvento, pedidoId, 14470));
                yield new StreamDto.ResultadoPrueba(stream, variante,
                        "El mismo idEvento llega dos veces: el primero se procesa y el segundo se descarta como "
                                + "duplicado en cada aplicacion (la venta se cuenta una sola vez).",
                        List.of(streamAdmin.publicarCrudo(stream, idEvento, clave, contenido),
                                streamAdmin.publicarCrudo(stream, idEvento, clave, contenido)));
            }

            case INVALIDO -> {
                Map<String, Object> evento = pedidoCreado(idEvento, pedidoId, -500);
                evento.remove("idEvento");
                yield new StreamDto.ResultadoPrueba(stream, variante,
                        "Falla la validacion del DTO (sin idEvento, total negativo): va directo al stream de "
                                + "fallidos con tipoFalla=INVALIDO, sin reintentos.",
                        List.of(streamAdmin.publicarCrudo(stream, null, clave, json(evento))));
            }

            case ILEGIBLE -> new StreamDto.ResultadoPrueba(stream, variante,
                    "No es JSON: va al stream de fallidos con tipoFalla=ILEGIBLE. El shard sigue avanzando.",
                    List.of(streamAdmin.publicarCrudo(stream, null, clave, "pedido=" + pedidoId + ";total=???")));

            case INCOHERENTE -> new StreamDto.ResultadoPrueba(stream, variante,
                    "El total no cuadra con los items. El tablero de ventas lo rechaza (NO_RECUPERABLE, al "
                            + "stream de fallidos) pero cocina lo procesa: cada aplicacion decide por su cuenta.",
                    List.of(streamAdmin.publicarCrudo(stream, idEvento, clave,
                            json(pedidoCreado(idEvento, pedidoId, 1)))));
        };
    }

    private Map<String, Object> pedidoCreado(String idEvento, long pedidoId, long total) {
        return StreamAdminService.mapa(
                "version", 1,
                "idEvento", idEvento,
                "tipo", "PEDIDO_CREADO",
                "pedidoId", pedidoId,
                "estadoAnterior", null,
                "estadoNuevo", "PENDIENTE",
                "clienteId", 1,
                "total", total,
                "cantidadItems", 3,
                "items", List.of(
                        StreamAdminService.mapa("productoId", 9001, "productoNombre", "Hamburguesa de prueba",
                                "cantidad", 2, "precioUnitario", 5990),
                        StreamAdminService.mapa("productoId", 9002, "productoNombre", "Papas de prueba",
                                "cantidad", 1, "precioUnitario", 2490)),
                "ocurridoEn", Instant.now().toString(),
                "origen", "admin-prueba");
    }

    private String json(Object valor) {
        return objectMapper.writeValueAsString(valor);
    }
}
