package com.pedidos360.mensajeria.comun;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Tolerancia a mensajes duplicados.
 *
 * <p>SQS estandar garantiza entrega <em>al menos una vez</em>, no exactamente
 * una: el mismo evento puede llegar dos veces, y tambien llega de nuevo cuando
 * se reprocesa desde la DLQ. Sin este control, un pedido entregado podria
 * generar dos boletas o dos notificaciones.
 *
 * <p>La clave es la cola mas el {@code idEvento} que genera el productor, no
 * el id de mensaje de SQS: el id de SQS cambia en cada reenvio, el del evento
 * no. La cola es parte de la clave porque el fanout entrega el mismo evento a
 * las tres colas: con solo el idEvento, el segundo consumidor de la misma
 * instancia lo tomaria por duplicado y lo descartaria sin procesarlo.
 *
 * <p>Limitacion conocida: el registro es local al proceso. Con dos instancias
 * corriendo, cada una lleva su propio registro, asi que un duplicado entregado
 * a la otra instancia no se detecta. Para produccion esto deberia vivir en un
 * almacen compartido (una tabla con el idEvento como clave unica, o DynamoDB).
 * Para el alcance de esta evaluacion se resuelve en memoria y se documenta.
 */
@Component
public class RegistroEventosProcesados {

    /** Tope de ids recordados. Evita que el registro crezca sin control. */
    private static final int MAXIMO_RECORDADO = 5_000;

    private final Set<String> procesados = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> masAntiguo) {
                    return size() > MAXIMO_RECORDADO;
                }
            }));

    public boolean yaFueProcesado(String idCola, String idEvento) {
        return idEvento != null && procesados.contains(clave(idCola, idEvento));
    }

    public void marcarProcesado(String idCola, String idEvento) {
        if (idEvento != null) {
            procesados.add(clave(idCola, idEvento));
        }
    }

    private static String clave(String idCola, String idEvento) {
        return idCola + ":" + idEvento;
    }

    public int cantidadRecordada() {
        return procesados.size();
    }
}
