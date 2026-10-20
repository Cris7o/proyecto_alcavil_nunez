@echo off
rem ===========================================================================
rem  Pedido360 - Levantar todo para la presentacion
rem ===========================================================================
rem  Un doble clic y queda listo:
rem
rem    1. administrador  (puerto 8080)  crea la topologia en AWS
rem    2. consumidor A   (puerto 8081)
rem    3. consumidor B   (puerto 8082)
rem    4. frontend       (puerto 4173)
rem
rem  Las cuatro ventanas quedan minimizadas. Al final se abre el navegador.
rem
rem  ANTES: iniciar el lab de AWS Academy y pegar las credenciales en
rem         %USERPROFILE%\.aws\credentials  (boton "AWS Details" del lab)
rem ===========================================================================

setlocal

set RAIZ=%~dp0..
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot
set JAVA=%JAVA_HOME%\bin\java.exe
set AWS_REGION=us-east-1

rem --- Memoria acotada: cuatro JVM en un equipo de escritorio -----------------
set OPCIONES_JVM=-Xmx256m -XX:TieredStopAtLevel=1

rem --- Comprobaciones previas ------------------------------------------------

if not exist "%JAVA%" (
  echo [ERROR] No se encontro Java en:
  echo         %JAVA%
  echo         Edita la variable JAVA_HOME en este archivo.
  pause
  exit /b 1
)

if not exist "%USERPROFILE%\.aws\credentials" (
  echo.
  echo [ATENCION] No hay credenciales de AWS en:
  echo            %USERPROFILE%\.aws\credentials
  echo.
  echo  Abre el lab de AWS Academy, aprieta "AWS Details", copia el bloque
  echo  que empieza con [default] y pegalo en ese archivo.
  echo.
  echo  Sin eso los servicios arrancan pero no pueden hablar con SQS.
  echo.
  pause
)

set ADMIN_JAR=%RAIZ%\admin-service\target\admin-service-0.0.1-SNAPSHOT.jar
set MENSAJERIA_JAR=%RAIZ%\mensajeria-service\target\mensajeria-service-0.0.1-SNAPSHOT.jar

if not exist "%ADMIN_JAR%" goto :sin_construir
if not exist "%MENSAJERIA_JAR%" goto :sin_construir
if not exist "%RAIZ%\frontend\dist\index.html" goto :sin_construir

rem --- 1. Administrador ------------------------------------------------------
rem  Arranca primero porque declara la topologia: el topico, las tres colas,
rem  las tres DLQ, las suscripciones con sus filtros y las alarmas.

echo.
echo [1/4] Administrador en el puerto 8080...
start "Pedido360 - administrador" /min "%JAVA%" %OPCIONES_JVM% -jar "%ADMIN_JAR%"

rem  Esperar a que responda antes de levantar los consumidores: si las colas
rem  todavia no existen, los consumidores fallan al arrancar.
echo       esperando a que cree la topologia...
set INTENTOS=0
:esperar_admin
set /a INTENTOS+=1
timeout /t 2 /nobreak >nul
curl -s -o nul -m 2 http://localhost:8080/admin/topologia
if not errorlevel 1 goto :admin_listo
if %INTENTOS% geq 30 (
  echo       [ATENCION] El administrador no respondio en 60 segundos.
  echo                  Se sigue igual: mira su ventana para ver que paso.
  goto :admin_listo
)
goto :esperar_admin

:admin_listo
echo       listo.

rem --- 2 y 3. Los dos consumidores -------------------------------------------
rem  Dos instancias sobre las mismas colas. SQS reparte los mensajes entre
rem  ellas; si se detiene una, la otra sigue sin perder nada.

echo [2/4] Consumidor A en el puerto 8081...
start "Pedido360 - consumidor A" /min "%JAVA%" %OPCIONES_JVM% -jar "%MENSAJERIA_JAR%" --server.port=8081 --pedido360.instancia=A

echo [3/4] Consumidor B en el puerto 8082...
start "Pedido360 - consumidor B" /min "%JAVA%" %OPCIONES_JVM% -jar "%MENSAJERIA_JAR%" --server.port=8082 --pedido360.instancia=B

rem --- 4. Frontend -----------------------------------------------------------
rem  Se sirve local, no desde Netlify: el panel de mensajeria le habla al
rem  administrador que corre en este equipo, y un sitio HTTPS llamando al
rem  propio equipo es algo que el navegador bloquea o pregunta.
rem  http://localhost cuenta como contexto seguro, asi que Cognito funciona.

echo [4/4] Frontend en el puerto 4173...
start "Pedido360 - frontend" /min cmd /c "cd /d "%RAIZ%\frontend" && npm run preview -- --port 4173"

timeout /t 6 /nobreak >nul
start "" http://localhost:4173

echo.
echo ===========================================================
echo  Todo levantado.
echo.
echo   Aplicacion     http://localhost:4173
echo   Administrador  http://localhost:8080/admin/topologia
echo   Consumidor A   http://localhost:8081
echo   Consumidor B   http://localhost:8082
echo.
echo  Entra como admin@pedido360.cl y abre la pestana Mensajeria.
echo.
echo  Para apagar todo: detener.bat
echo ===========================================================
echo.
pause
exit /b 0

:sin_construir
echo.
echo [ERROR] Falta construir el proyecto.
echo         Corre primero: construir.bat
echo.
pause
exit /b 1
