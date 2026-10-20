@echo off
rem ===========================================================================
rem  Pedido360 - Construir todo
rem ===========================================================================
rem  Compila los tres microservicios y el frontend, y deja los .jar y el dist
rem  listos para que presentacion.bat levante todo en segundos.
rem
rem  Esto se corre UNA VEZ, cuando cambia el codigo. No el dia de la
rem  presentacion.
rem ===========================================================================

setlocal

set RAIZ=%~dp0..
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot

if not exist "%JAVA_HOME%" (
  echo [ERROR] No se encontro el JDK en:
  echo         %JAVA_HOME%
  echo         Edita la variable JAVA_HOME en este archivo.
  pause
  exit /b 1
)

echo.
echo === 1 de 4: servicio de pedidos ===
call "%RAIZ%\backend\mvnw.cmd" -f "%RAIZ%\backend\pom.xml" -q -B package -DskipTests
if errorlevel 1 goto :fallo

echo === 2 de 4: consumidores ===
call "%RAIZ%\mensajeria-service\mvnw.cmd" -f "%RAIZ%\mensajeria-service\pom.xml" -q -B package -DskipTests
if errorlevel 1 goto :fallo

echo === 3 de 4: administrador ===
call "%RAIZ%\admin-service\mvnw.cmd" -f "%RAIZ%\admin-service\pom.xml" -q -B package -DskipTests
if errorlevel 1 goto :fallo

echo === 4 de 4: frontend ===
pushd "%RAIZ%\frontend"
if not exist node_modules call npm ci --no-audit --no-fund
call npm run build
if errorlevel 1 (
  popd
  goto :fallo
)
popd

echo.
echo ===========================================================
echo  Todo construido. Ya se puede usar presentacion.bat
echo ===========================================================
pause
exit /b 0

:fallo
echo.
echo [ERROR] La construccion fallo. Revisa el mensaje de arriba.
pause
exit /b 1
