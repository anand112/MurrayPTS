@echo off
setlocal
rem ---------------------------------------------------------------------------
rem MURRAY PTS v2 - stop (Windows)
rem
rem Targets only the JVM whose command line launches this app's main class, so any other Java
rem process on the machine (an IDE, the Gradle daemon, Tomcat) is never touched. Use this for
rem the detached instance started by run.bat; a /foreground window just needs Ctrl+C.
rem
rem It asks politely first (taskkill without /F = close event, which lets the JVM run its
rem shutdown hook and close the SQLite pool), and only force-kills if the process is still
rem there 5 seconds later.
rem ---------------------------------------------------------------------------

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p = Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" |" ^
  "     Where-Object { $_.CommandLine -match 'mn\.murray\.pts\.ApplicationKt' };" ^
  "if (-not $p) { Write-Host '[PTS] not running.'; exit 0 };" ^
  "$ids = @($p | ForEach-Object { $_.ProcessId });" ^
  "foreach ($id in $ids) { Write-Host ('[PTS] closing PID ' + $id); taskkill /PID $id | Out-Null };" ^
  "Start-Sleep -Seconds 5;" ^
  "$left = $ids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue };" ^
  "foreach ($id in $left) { Write-Host ('[PTS] force-stopping PID ' + $id); Stop-Process -Id $id -Force };" ^
  "if (-not $left) { Write-Host '[PTS] stopped.'; exit 0 };" ^
  "Start-Sleep -Seconds 2;" ^
  "$dead = $left | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue };" ^
  "if ($dead) { Write-Host ('[PTS] could not stop: ' + ($dead -join ', ')); exit 1 };" ^
  "Write-Host '[PTS] stopped (after force).'"

endlocal
