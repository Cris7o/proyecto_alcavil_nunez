package com.pedidos360.mensajeria.documentos;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pedidos360.mensajeria.comun.ErrorNoRecuperableException;
import com.pedidos360.mensajeria.comun.ErrorRecuperableException;
import com.pedidos360.mensajeria.comun.EventoPedido;
import com.pedidos360.mensajeria.comun.TipoEvento;

/**
 * Dominio de documentos: comanda de cocina y boleta de entrega.
 *
 * <p>Esta es la razon por la que la mensajeria asincrona vale la pena en este
 * sistema. Generar un documento toma tiempo y puede fallar, y el cliente no
 * tiene por que esperarlo: confirma su pedido en milisegundos y el documento se
 * produce despues, por su cuenta.
 *
 * <p>El archivo se escribe en disco para que el documento generado sea algo que
 * se pueda abrir y mostrar, en lugar de una linea de log.
 */
@Service
public class DocumentoService {

    private static final Logger log = LoggerFactory.getLogger(DocumentoService.class);

    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path directorio;

    public DocumentoService(@Value("${pedido360.documentos.directorio}") Path directorio) {
        this.directorio = directorio;
    }

    public void generar(EventoPedido evento) {
        String contenido = switch (evento.tipo()) {
            case PEDIDO_CREADO -> comanda(evento);
            case PEDIDO_ENTREGADO -> boleta(evento);
            // El filtro de suscripcion deberia impedir que lleguemos aqui. Si
            // llega otro tipo, es un error de configuracion del filtro: no sirve
            // reintentarlo, hay que verlo en la DLQ.
            default -> throw new ErrorNoRecuperableException(
                    "El dominio de documentos no genera nada para el tipo " + evento.tipo());
        };

        String nombreArchivo = "%s-pedido-%d.txt".formatted(
                evento.tipo() == TipoEvento.PEDIDO_CREADO ? "comanda" : "boleta",
                evento.pedidoId());

        try {
            Files.createDirectories(directorio);
            Path archivo = directorio.resolve(nombreArchivo);
            Files.writeString(archivo, contenido, StandardCharsets.UTF_8);

            log.info("DOCUMENTO generado | {} | pedido #{} | idEvento={}",
                    archivo.toAbsolutePath(), evento.pedidoId(), evento.idEvento());

        } catch (Exception e) {
            // El disco puede estar lleno o bloqueado por otro proceso: eso se
            // resuelve solo a veces, asi que conviene reintentar.
            throw new ErrorRecuperableException(
                    "No se pudo escribir el documento " + nombreArchivo, e);
        }
    }

    private String comanda(EventoPedido evento) {
        return """
                ====================================
                       COMANDA DE COCINA
                ====================================
                Pedido      : #%d
                Cliente     : %s
                Recibido    : %s
                Total       : $%s
                ------------------------------------
                Estado      : %s
                idEvento    : %s
                ====================================
                """.formatted(
                evento.pedidoId(),
                evento.clienteNombre(),
                FORMATO_FECHA.format(evento.ocurridoEn()),
                formatearTotal(evento.total()),
                evento.estadoNuevo(),
                evento.idEvento());
    }

    private String boleta(EventoPedido evento) {
        return """
                ====================================
                      BOLETA DE ENTREGA
                ====================================
                Pedido      : #%d
                Cliente     : %s
                Correo      : %s
                Entregado   : %s
                ------------------------------------
                TOTAL       : $%s
                ------------------------------------
                idEvento    : %s
                ====================================
                """.formatted(
                evento.pedidoId(),
                evento.clienteNombre(),
                evento.clienteEmail(),
                FORMATO_FECHA.format(evento.ocurridoEn()),
                formatearTotal(evento.total()),
                evento.idEvento());
    }

    private String formatearTotal(BigDecimal total) {
        return total == null ? "0" : total.stripTrailingZeros().toPlainString();
    }
}
