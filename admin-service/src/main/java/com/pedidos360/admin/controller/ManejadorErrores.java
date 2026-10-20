package com.pedidos360.admin.controller;

import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import software.amazon.awssdk.services.sns.model.SnsException;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * Traduce los errores a respuestas HTTP con significado.
 *
 * <p>Sin esto, un recurso inexistente devolveria 500 y una excepcion del SDK
 * con su mensaje interno. Aqui cada caso recibe el codigo que le corresponde y
 * un mensaje legible.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    /** Una cola, topico o suscripcion que no existe. */
    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail noEncontrado(NoSuchElementException e) {
        return problema(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Errores que devuelve SQS. El mas comun en operacion es la purga
     * repetida, que SQS limita a una por minuto.
     */
    @ExceptionHandler(SqsException.class)
    public ProblemDetail errorDeSqs(SqsException e) {
        log.warn("SQS rechazo la operacion: {}", e.getMessage());
        return problema(estadoDe(e.statusCode()), mensajeDe(e.getMessage()));
    }

    @ExceptionHandler(SnsException.class)
    public ProblemDetail errorDeSns(SnsException e) {
        log.warn("SNS rechazo la operacion: {}", e.getMessage());
        return problema(estadoDe(e.statusCode()), mensajeDe(e.getMessage()));
    }

    /**
     * Cualquier otra falla. Se registra completa en el log y se devuelve un
     * mensaje sin detalles internos.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail errorInesperado(Exception e) {
        log.error("Error inesperado en el microservicio administrador", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR,
                "No se pudo completar la operacion. Revisa el log del servicio.");
    }

    private HttpStatus estadoDe(int codigoAws) {
        if (codigoAws == 400) {
            return HttpStatus.BAD_REQUEST;
        }
        if (codigoAws == 403) {
            return HttpStatus.FORBIDDEN;
        }
        if (codigoAws == 404) {
            return HttpStatus.NOT_FOUND;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    /** Recorta el ruido de trazas y request id que agrega el SDK. */
    private String mensajeDe(String mensaje) {
        if (mensaje == null) {
            return "AWS rechazo la operacion";
        }
        int corte = mensaje.indexOf(" (Service:");
        return corte > 0 ? mensaje.substring(0, corte) : mensaje;
    }

    private ProblemDetail problema(HttpStatus estado, String detalle) {
        return ProblemDetail.forStatusAndDetail(estado, detalle);
    }
}
