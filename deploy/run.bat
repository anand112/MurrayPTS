@echo off
setlocal enabledelayedexpansion
rem ---------------------------------------------------------------------------
rem MURRAY PTS v2 - start (Windows)
rem
rem   run.bat                  build the distribution if needed, start PTS detached,
rem                            then wait until the login page answers
rem   run.bat /foreground      same, but stay attached to this window (Ctrl+C stops)
rem
rem Configuration lives in .env next to the repository root; this script only has to
rem provide the two values Logback needs as REAL environment variables (it configures
rem itself before .env is parsed): PTS_LOG_DIR and PTS_LOG_LEVEL.
rem
rem Why not build\install\murray-pts\bin\murray-pts.bat? Gradle's Windows launcher
rem expands all ~70 dependency jars into one `set CLASSPATH=...` line, and cmd's 8191
rem character line limit turns that into "The input line is too long". The wildcard
rem classpath below lets the JVM expand the jars itself, so line length stops mattering.
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0.."
set "LIB=%ROOT%\build\install\murray-pts\lib"

pushd "%ROOT%"

where java >nul 2>&1
if errorlevel 1 (
    echo [PTS] java not found on PATH. Install JDK/JRE 21 or set JAVA_HOME.
    popd & exit /b 1
)

rem Logback reads these directly, so they must exist in the environment, not only in .env.
if not defined PTS_LOG_DIR  set "PTS_LOG_DIR=%ROOT%\logs"
if not defined PTS_LOG_LEVEL set "PTS_LOG_LEVEL=INFO"

if not exist "%LIB%\murray-pts-2.0.0.jar" (
    echo [PTS] No distribution in build\install - running gradlew installDist ...
    call gradlew.bat installDist || (echo [PTS] build failed & popd & exit /b 1)
)

if "%1"=="/foreground" (
    echo [PTS] starting in the foreground; Ctrl+C to stop.
    java -Dfile.encoding=UTF-8 -Duser.timezone=UTC %PTS_JAVA_OPTS% -cp "%LIB%\*" mn.murray.pts.ApplicationKt
    popd
    exit /b %ERRORLEVEL%
)

echo [PTS] starting detached (minimised window) ...
start "MURRAY PTS" /min cmd //c "java -Dfile.encoding=UTF-8 -Duser.timezone=UTC %PTS_JAVA_OPTS% -cp "%LIB%\*" mn.murray.pts.ApplicationKt"

rem Poll the login page instead of guessing how long the JVM needs.
set "PORT=%PTS_PORT%"
if not defined PORT for /f "tokens=2 delims==" %%a in ('findstr /b /c:"PTS_PORT" .env 2^>nul') do set "PORT=%%~a"
if not defined PORT set "PORT=8080"

echo [PTS] waiting for http://127.0.0.1:!PORT!/ ...
for /l %%i in (1,1,30) do (
    curl -fsS -o nul "http://127.0.0.1:!PORT!/" 2>nul && (
        echo [PTS] up on port !PORT!.  Log: !PTS_LOG_DIR!\pts.log   Stop with stop.bat
        popd & exit /b 0
    )
    timeout /t 1 /nobreak >nul
)

echo [PTS] still not answering after 30s - check !PTS_LOG_DIR!\pts.log
popd
exit /b 1
