@echo off
rem ===========================================================================
rem  Pedido360 - Eliminar la plataforma de streaming (fin de la sesion)
rem ===========================================================================
rem  Kinesis cobra por shard y por hora mientras el stream exista: 7 shards
rem  encendidos toda la noche consumen credito del Learner Lab sin hacer nada.
rem
rem  Elimina los 3 streams, la alarma, la tabla de estado y las tablas de
rem  coordinacion de la KCL. Las tablas se borran a proposito: un stream
rem  recreado repite los ids de shard (shardId-000000000000...), y la KCL
rem  intentaria seguir desde checkpoints de un stream que ya no existe.
rem
rem    streaming-eliminar.bat               todo (recomendado)
rem    streaming-eliminar.bat solo-streams  conserva las tablas DynamoDB
rem
rem  Al recrear todo, la KCL vuelve a crear sus tablas y DynamoDB tarda unos
rem  10 minutos en dejar listos sus indices: levantar con tiempo.
rem
rem  Requiere el administrador corriendo en el puerto 8080.
rem ===========================================================================

set TABLAS=true
if /i "%1"=="solo-streams" set TABLAS=false

echo Eliminando la plataforma de streaming (incluir tablas: %TABLAS%)...
curl -s -m 120 -X DELETE "http://localhost:8080/admin/streams/topologia?incluirTablas=%TABLAS%"
echo.
pause
