package com.pedidos360.streaming.control;

import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import software.amazon.awssdk.core.exception.SdkException;

/** Traduce los errores de la API REST de este servicio a respuestas con significado. */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail noEncontrado(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalido(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** DynamoDB no respondio al leer un tablero. */
    @ExceptionHandler(SdkException.class)
    public ProblemDetail errorDeAws(SdkException e) {
        log.warn("AWS rechazo la consulta: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "No se pudo leer el estado en DynamoDB. Revisa credenciales y que la tabla exista.");
    }
}
