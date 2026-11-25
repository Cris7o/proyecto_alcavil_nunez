package com.pedidos360.streaming.comun;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Almacen del estado derivado de los streams, compartido por todas las
 * instancias.
 *
 * <p>Hace falta porque cada instancia procesa solo algunos shards: si el
 * tablero viviera en memoria, cada instancia veria una parte distinta.
 */
public interface RepositorioEstado {

    /**
     * Aplica los cambios de un evento una sola vez por aplicacion consumidora.
     *
     * <p>Los cambios y la marca de procesado se escriben en una sola transaccion
     * condicionada a que la marca no exista. Si el evento ya se proceso, no se
     * aplica nada y se devuelve {@link ResultadoAplicacion#DUPLICADO}.
     *
     * @throws ErrorTransitorioException si la base no respondio o hubo conflicto
     *         con otra escritura simultanea: se puede reintentar.
     */
    ResultadoAplicacion aplicarUnaVez(String aplicacion, String idEvento, List<CambioEstado> cambios);

    /** Todos los items de una clave de particion, con sus atributos. */
    List<Map<String, Object>> leerParticion(String pk);

    Optional<Map<String, Object>> leerItem(String pk, String sk);
}
