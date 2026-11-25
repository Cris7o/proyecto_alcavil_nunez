package com.pedidos360.streaming.persistencia;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pedidos360.streaming.comun.CambioEstado;
import com.pedidos360.streaming.comun.ErrorTransitorioException;
import com.pedidos360.streaming.comun.RepositorioEstado;
import com.pedidos360.streaming.comun.ResultadoAplicacion;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Estado derivado en DynamoDB, con idempotencia por transaccion.
 *
 * <p>Cada evento se aplica con un {@code TransactWriteItems} que contiene:
 * <ol>
 * <li>la marca {@code PROCESADO#<aplicacion> / <idEvento>}, condicionada a que
 * no exista, y
 * <li>los cambios que pidio el dominio.
 * </ol>
 * Si la marca ya existe, DynamoDB cancela la transaccion completa: el evento es
 * un duplicado y no se suma nada. Si la transaccion se cancela por otra razon
 * (conflicto con otra escritura, throttling), es un error transitorio y se
 * reintenta.
 *
 * <p>La marca vive mas que la retencion del stream (ver
 * {@code errores.recordar-procesados-horas}): un evento releido mientras sigue
 * en el stream siempre se reconoce como duplicado. Despues, el TTL de DynamoDB
 * la borra sola.
 */
public class DynamoRepositorioEstado implements RepositorioEstado {

    private static final Logger log = LoggerFactory.getLogger(DynamoRepositorioEstado.class);

    static final String ATRIBUTO_PK = "pk";
    static final String ATRIBUTO_SK = "sk";
    /** Atributo con el instante (epoch en segundos) en que DynamoDB borra el item. */
    static final String ATRIBUTO_TTL = "expiraEn";
    static final String PREFIJO_PROCESADO = "PROCESADO#";

    private static final String CONDICION_DUPLICADO = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;
    private final String tabla;
    private final long horasRecordarProcesados;

    public DynamoRepositorioEstado(DynamoDbClient dynamo, String tabla, long horasRecordarProcesados) {
        this.dynamo = dynamo;
        this.tabla = tabla;
        this.horasRecordarProcesados = horasRecordarProcesados;
    }

    @Override
    public ResultadoAplicacion aplicarUnaVez(String aplicacion, String idEvento, List<CambioEstado> cambios) {
        List<TransactWriteItem> items = new ArrayList<>(cambios.size() + 1);
        items.add(marcaDeProcesado(aplicacion, idEvento));
        cambios.forEach(cambio -> items.add(TransactWriteItem.builder().update(aUpdate(cambio)).build()));

        try {
            // No se usa clientRequestToken: si el SDK reintenta tras un timeout
            // y la primera llamada si llego, la marca ya existe y el reintento
            // se reconoce como duplicado. La marca cubre ese caso sola.
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(items)
                    .build());
            return ResultadoAplicacion.APLICADO;

        } catch (TransactionCanceledException e) {
            List<CancellationReason> razones = e.cancellationReasons();
            if (!razones.isEmpty() && CONDICION_DUPLICADO.equals(razones.get(0).code())) {
                return ResultadoAplicacion.DUPLICADO;
            }
            throw new ErrorTransitorioException("Transaccion cancelada por DynamoDB: " + codigos(razones), e);

        } catch (SdkException e) {
            throw new ErrorTransitorioException("DynamoDB no respondio: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> leerParticion(String pk) {
        List<Map<String, Object>> resultado = new ArrayList<>();
        Map<String, AttributeValue> desde = null;

        do {
            QueryResponse respuesta = dynamo.query(QueryRequest.builder()
                    .tableName(tabla)
                    .keyConditionExpression("#pk = :pk")
                    .expressionAttributeNames(Map.of("#pk", ATRIBUTO_PK))
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(pk)))
                    .exclusiveStartKey(desde)
                    .build());
            respuesta.items().forEach(item -> resultado.add(aMapa(item)));
            desde = respuesta.hasLastEvaluatedKey() && !respuesta.lastEvaluatedKey().isEmpty()
                    ? respuesta.lastEvaluatedKey()
                    : null;
        } while (desde != null);

        return resultado;
    }

    @Override
    public Optional<Map<String, Object>> leerItem(String pk, String sk) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(tabla)
                .key(Map.of(ATRIBUTO_PK, AttributeValue.fromS(pk), ATRIBUTO_SK, AttributeValue.fromS(sk)))
                .build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(aMapa(item));
    }

    private TransactWriteItem marcaDeProcesado(String aplicacion, String idEvento) {
        Map<String, AttributeValue> marca = new HashMap<>();
        marca.put(ATRIBUTO_PK, AttributeValue.fromS(PREFIJO_PROCESADO + aplicacion));
        marca.put(ATRIBUTO_SK, AttributeValue.fromS(idEvento));
        marca.put("procesadoEn", AttributeValue.fromS(Instant.now().toString()));
        marca.put(ATRIBUTO_TTL, AttributeValue.fromN(Long.toString(expiracion(horasRecordarProcesados))));

        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tabla)
                        .item(marca)
                        .conditionExpression("attribute_not_exists(#pk)")
                        .expressionAttributeNames(Map.of("#pk", ATRIBUTO_PK))
                        .build())
                .build();
    }

    /** Traduce un cambio del dominio a un {@code Update} de DynamoDB. */
    Update aUpdate(CambioEstado cambio) {
        Map<String, String> nombres = new HashMap<>();
        Map<String, AttributeValue> valores = new HashMap<>();
        List<String> sets = new ArrayList<>();
        List<String> adds = new ArrayList<>();

        int i = 0;
        for (Map.Entry<String, Number> contador : cambio.sumar().entrySet()) {
            nombres.put("#v" + i, contador.getKey());
            valores.put(":v" + i, AttributeValue.fromN(numero(contador.getValue())));
            adds.add("#v" + i + " :v" + i);
            i++;
        }
        for (Map.Entry<String, Object> atributo : cambio.fijar().entrySet()) {
            nombres.put("#v" + i, atributo.getKey());
            valores.put(":v" + i, aValor(atributo.getValue()));
            sets.add("#v" + i + " = :v" + i);
            i++;
        }
        for (Map.Entry<String, Object> atributo : cambio.fijarSiNoExiste().entrySet()) {
            nombres.put("#v" + i, atributo.getKey());
            valores.put(":v" + i, aValor(atributo.getValue()));
            sets.add("#v" + i + " = if_not_exists(#v" + i + ", :v" + i + ")");
            i++;
        }

        nombres.put("#ttl", ATRIBUTO_TTL);
        valores.put(":ttl", AttributeValue.fromN(Long.toString(expiracion(cambio.horasDeVida()))));
        sets.add("#ttl = :ttl");

        StringBuilder expresion = new StringBuilder("SET ").append(String.join(", ", sets));
        if (!adds.isEmpty()) {
            expresion.append(" ADD ").append(String.join(", ", adds));
        }

        return Update.builder()
                .tableName(tabla)
                .key(Map.of(ATRIBUTO_PK, AttributeValue.fromS(cambio.pk()),
                        ATRIBUTO_SK, AttributeValue.fromS(cambio.sk())))
                .updateExpression(expresion.toString())
                .expressionAttributeNames(nombres)
                .expressionAttributeValues(valores)
                .build();
    }

    private static AttributeValue aValor(Object valor) {
        if (valor instanceof Number numero) {
            return AttributeValue.fromN(numero(numero));
        }
        if (valor instanceof Boolean booleano) {
            return AttributeValue.fromBool(booleano);
        }
        return AttributeValue.fromS(valor.toString());
    }

    private static String numero(Number numero) {
        return numero instanceof BigDecimal decimal ? decimal.toPlainString() : numero.toString();
    }

    private static Map<String, Object> aMapa(Map<String, AttributeValue> item) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        item.forEach((nombre, valor) -> {
            if (valor.s() != null) {
                mapa.put(nombre, valor.s());
            } else if (valor.n() != null) {
                mapa.put(nombre, new BigDecimal(valor.n()));
            } else if (valor.bool() != null) {
                mapa.put(nombre, valor.bool());
            }
        });
        return mapa;
    }

    private static long expiracion(long horas) {
        return Instant.now().plusSeconds(horas * 3600).getEpochSecond();
    }

    private static String codigos(List<CancellationReason> razones) {
        List<String> codigos = razones.stream().map(CancellationReason::code).toList();
        log.debug("Razones de cancelacion: {}", codigos);
        return codigos.toString();
    }
}
