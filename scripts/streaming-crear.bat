@echo off
rem ===========================================================================
rem  Pedido360 - Crear (o ajustar) la plataforma de streaming en AWS
rem ===========================================================================
rem  Le pide al administrador (que debe estar corriendo en el puerto 8080) que
rem  aplique lo declarado en admin-service/src/main/resources/application.yml:
rem
rem    - pedido360-pedidos    3 shards, 7 dias de retencion
rem    - pedido360-actividad  3 shards, 24 horas de retencion
rem    - pedido360-fallidos   1 shard,  7 dias de retencion
rem    - tabla DynamoDB pedido360-streaming-estado (con TTL)
rem    - alarma pedido360-pedidos-retraso-consumo
rem
rem  Es idempotente: correrlo dos veces no crea nada repetido.
rem ===========================================================================

echo Creando o ajustando los streams (puede tardar hasta un minuto)...
curl -s -m 240 -X POST http://localhost:8080/admin/streams/topologia
echo.
echo.
echo Estado actual:
curl -s -m 30 http://localhost:8080/admin/streams
echo.
pause
