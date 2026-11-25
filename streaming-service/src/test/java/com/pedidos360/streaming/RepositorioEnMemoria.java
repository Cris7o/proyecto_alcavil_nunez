package com.pedidos360.streaming;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.pedidos360.streaming.comun.CambioEstado;
import com.pedidos360.streaming.comun.ErrorTransitorioException;
import com.pedidos360.streaming.comun.RepositorioEstado;
import com.pedidos360.streaming.comun.ResultadoAplicacion;

/**
 * Doble de prueba del repositorio: misma semantica que DynamoDB (aplicar una
 * sola vez por aplicacion e idEvento) pero en memoria.
 */
public class RepositorioEnMemoria implements RepositorioEstado {

    private final Set<String> procesados = new HashSet<>();
    private final Map<String, Map<String, Object>> items = new TreeMap<>();
    private int fallasTransitoriasPendientes;

    /** Las proximas {@code n} escrituras fallaran como lo haria DynamoDB ocupado. */
    public void fallarProximas(int n) {
        this.fallasTransitoriasPendientes = n;
    }

    @Override
    public synchronized ResultadoAplicacion aplicarUnaVez(String aplicacion, String idEvento,
            List<CambioEstado> cambios) {
        if (fallasTransitoriasPendientes > 0) {
            fallasTransitoriasPendientes--;
            throw new ErrorTransitorioException("Transaccion cancelada por DynamoDB: [TransactionConflict]");
        }
        if (!procesados.add(aplicacion + "|" + idEvento)) {
            return ResultadoAplicacion.DUPLICADO;
        }
        for (CambioEstado cambio : cambios) {
            Map<String, Object> item = items.computeIfAbsent(cambio.pk() + "|" + cambio.sk(), clave -> {
                Map<String, Object> nuevo = new LinkedHashMap<>();
                nuevo.put("pk", cambio.pk());
                nuevo.put("sk", cambio.sk());
                return nuevo;
            });
            cambio.sumar().forEach((nombre, valor) -> item.merge(nombre, decimal(valor),
                    (a, b) -> ((BigDecimal) a).add((BigDecimal) b)));
            cambio.fijar().forEach((nombre, valor) -> item.put(nombre, normalizar(valor)));
            cambio.fijarSiNoExiste().forEach((nombre, valor) -> item.putIfAbsent(nombre, normalizar(valor)));
        }
        return ResultadoAplicacion.APLICADO;
    }

    @Override
    public synchronized List<Map<String, Object>> leerParticion(String pk) {
        List<Map<String, Object>> resultado = new ArrayList<>();
        items.forEach((clave, item) -> {
            if (pk.equals(item.get("pk"))) {
                resultado.add(new LinkedHashMap<>(item));
            }
        });
        return resultado;
    }

    @Override
    public synchronized Optional<Map<String, Object>> leerItem(String pk, String sk) {
        return Optional.ofNullable(items.get(pk + "|" + sk)).map(LinkedHashMap::new);
    }

    private static BigDecimal decimal(Number numero) {
        return new BigDecimal(numero.toString());
    }

    /** Igual que DynamoDB: los numeros vuelven como BigDecimal. */
    private static Object normalizar(Object valor) {
        return valor instanceof Number numero ? decimal(numero) : valor;
    }
}
