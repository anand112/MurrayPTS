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
rem `cmd /c` here, not `cmd //c`: a .bat is parsed by cmd itself, so the MSYS-style double slash that a
rem Git Bash caller needs for ITS own arguments is taken literally inside this file - `//c` starts an
rem interactive cmd in the minimized window and PTS never launches.
start "MURRAY PTS" /min cmd /c "java -Dfile.encoding=UTF-8 -Duser.timezone=UTC %PTS_JAVA_OPTS% -cp "%LIB%\*" mn.murray.pts.ApplicationKt"

rem Poll the login page instead of guessing how long the JVM needs.
rem `ping`, not `timeout`, for the one-second wait. Even spelled `timeout.exe` the wait came out wrong when
rem this script was started from Git Bash: that shell's PATH puts Git's own usr/bin first, and it ships a
rem coreutils `timeout.exe` which rejects `/t`, sleeps nothing, and so burns all 30 tries in about a second
rem and prints "still not answering" while PTS is coming up fine. `ping -n 2` is a real one-second pause in
rem every shell and every Windows install, and it asks nothing of the local network because it targets itself.
set "PORT=%PTS_PORT%"
if not defined PORT for /f "tokens=2 delims==" %%a in ('findstr /b /c:"PTS_PORT" .env 2^>nul') do set "PORT=%%~a"
if not defined PORT set "PORT=8080"

echo [PTS] waiting for http://127.0.0.1:!PORT!/ ...
for /l %%i in (1,1,30) do (
    curl -fsS -o nul "http://127.0.0.1:!PORT!/" 2>nul && (
        echo [PTS] up on port !PORT!.  Log: !PTS_LOG_DIR!\pts.log   Stop with stop.bat
        popd & exit /b 0
    )
    ping -n 2 127.0.0.1 >nul
)

echo [PTS] still not answering after 30s - check !PTS_LOG_DIR!\pts.log
popd
exit /b 1
