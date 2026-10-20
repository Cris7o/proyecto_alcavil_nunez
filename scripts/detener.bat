@echo off
rem ===========================================================================
rem  Pedido360 - Apagar todo lo que levanto presentacion.bat
rem ===========================================================================
rem  Cierra las cuatro ventanas por su titulo. No toca ningun otro Java ni
rem  ningun otro Node que este corriendo en el equipo.
rem ===========================================================================

echo Cerrando los servicios de Pedido360...

taskkill /fi "WINDOWTITLE eq Pedido360 - administrador*" /t /f >nul 2>&1
taskkill /fi "WINDOWTITLE eq Pedido360 - consumidor A*"  /t /f >nul 2>&1
taskkill /fi "WINDOWTITLE eq Pedido360 - consumidor B*"  /t /f >nul 2>&1
taskkill /fi "WINDOWTITLE eq Pedido360 - frontend*"      /t /f >nul 2>&1

echo Listo.
timeout /t 2 /nobreak >nul
