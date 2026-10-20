@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Pixel Dungeon Save Sync
rem Keep the existing per-user app workspace; no config or game saves are bundled.
set "JAVA_EXE=java.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "JAVA_INFO=%TEMP%\pdsync-java-%RANDOM%-%RANDOM%.txt"
"%JAVA_EXE%" -XshowSettings:properties -version > "%JAVA_INFO%" 2>&1
if errorlevel 1 goto java_missing
set "JAVA_MAJOR="
for /f "tokens=3" %%V in ('findstr /C:"java.specification.version =" "%JAVA_INFO%"') do set "JAVA_MAJOR=%%V"
if not defined JAVA_MAJOR goto java_missing
for /f "tokens=1 delims=." %%V in ("%JAVA_MAJOR%") do set "JAVA_MAJOR=%%V"
if %JAVA_MAJOR% LSS 17 goto java_missing
findstr /C:"os.arch = amd64" /C:"os.arch = x86_64" "%JAVA_INFO%" >nul
if errorlevel 1 goto java_missing
del /q "%JAVA_INFO%" >nul 2>&1
pushd "%~dp0"
"%JAVA_EXE%" -cp "%~dp0lib\*" pdsync.MainKt
set "APP_EXIT=%ERRORLEVEL%"
popd
if "%APP_EXIT%"=="0" exit /b 0
echo.
echo The app exited with error %APP_EXIT%. Keep the error above for diagnosis.
pause
exit /b %APP_EXIT%

:java_missing
echo This build requires an existing Windows x64 Java 17 or newer.
echo Java 21 x64 is recommended. Set JAVA_HOME or add java.exe to PATH.
echo No Java runtime is included or downloaded by this launcher.
if exist "%JAVA_INFO%" type "%JAVA_INFO%"
if exist "%JAVA_INFO%" del /q "%JAVA_INFO%" >nul 2>&1
pause
exit /b 1
