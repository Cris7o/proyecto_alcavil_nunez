package com.pedidos360.backend.streaming;

/**
 * Lo unico que la capa de negocio conoce del stream de pedidos.
 *
 * <p>Convive con {@code PublicadorEventos} (SNS) sin reemplazarlo: SNS y SQS
 * siguen entregando cada evento a sus colas, y Kinesis lo agrega a un registro
 * ordenado y retenido que pueden releer varias aplicaciones.
 */
public interface PublicadorStreamPedidos {

    /**
     * Publica el evento. Nunca propaga una falla de infraestructura: el pedido
     * ya quedo guardado y fallar aqui haria que el cliente viera un error sobre
     * un pedido que si existe. La falla queda en el log con el idEvento.
     */
    void publicar(EventoPedidoStream evento);
}
