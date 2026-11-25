package com.pedidos360.streaming.persistencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.pedidos360.streaming.comun.CambioEstado;

import software.amazon.awssdk.services.dynamodb.model.Update;

/** Verifica la traduccion de un cambio del dominio a la expresion de DynamoDB. */
class DynamoRepositorioEstadoTest {

    private final DynamoRepositorioEstado repositorio = new DynamoRepositorioEstado(null, "tabla", 192);

    @Test
    void sumaFijaYFijaSoloSiNoExisteEnUnaSolaExpresion() {
        CambioEstado cambio = CambioEstado.en("SEGUIMIENTO", "PEDIDO#1")
                .sumar("eventos", 1)
                .fijar("estado", "LISTO")
                .fijarSiNoExiste("en_LISTO", "2026-11-24T18:00:00Z")
                .build(48);

        Update update = repositorio.aUpdate(cambio);

        assertThat(update.tableName()).isEqualTo("tabla");
        assertThat(update.updateExpression())
                .startsWith("SET ")
                .contains("= if_not_exists(")
                .contains("#ttl = :ttl")
                .contains(" ADD #v0 :v0");
        assertThat(update.expressionAttributeNames()).containsValues("eventos", "estado", "en_LISTO", "expiraEn");
        assertThat(update.key().get("pk").s()).isEqualTo("SEGUIMIENTO");
    }

    @Test
    void losMontosDecimalesSeEscribenSinNotacionCientifica() {
        CambioEstado cambio = CambioEstado.en("VENTAS#2026-11-24", "RESUMEN")
                .sumar("monto", new BigDecimal("1E+4"))
                .build(24);

        Update update = repositorio.aUpdate(cambio);

        assertThat(update.expressionAttributeValues().get(":v0").n()).isEqualTo("10000");
    }
}
