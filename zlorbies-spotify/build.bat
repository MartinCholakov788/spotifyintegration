@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
echo Building in: %CD%
echo.

set "JDK="
call :find

if not defined JDK (
  echo Java 21 was not found on this PC. Minecraft 1.21.11 mods need it to be built.
  echo.
  echo Install it automatically now? This uses Windows Package Manager and may ask for permission.
  set "ANS="
  set /p "ANS=Type Y and press Enter to install, or just press Enter to skip: "
  if /i "!ANS!"=="Y" (
    winget install -e --id EclipseAdoptium.Temurin.21.JDK --accept-package-agreements --accept-source-agreements
    call :find
  )
)

if not defined JDK (
  echo.
  echo Still no Java 21. Install it by hand, then run this file again:
  echo   1. Open https://adoptium.net/temurin/releases/?version=21
  echo   2. Pick Windows, x64, JDK, .msi and run it
  echo   3. On the "Custom Setup" page turn on "Set JAVA_HOME variable"
  echo.
  pause
  exit /b 1
)

set "JAVA_HOME=!JDK!"
set "PATH=!JAVA_HOME!\bin;!PATH!"
set "GRADLE_USER_HOME=C:\gradle-home"
echo Using Java 21 from: !JAVA_HOME!
"!JAVA_HOME!\bin\java" -version
echo.

if exist "dist" rmdir /s /q "dist"
if exist "build\libs" del /q "build\libs\*.jar" >nul 2>&1

call "%~dp0gradlew.bat" build --console=plain
set "RESULT=!errorlevel!"
echo.
if not "!RESULT!"=="0" (
  echo ==========================================================
  echo   BUILD FAILED - the mod was NOT built.
  echo   Scroll up to the first line with "error:" or "What went wrong:"
  echo   and send it to Claude. Nothing was put in the dist folder.
  echo ==========================================================
  pause
  exit /b 1
)

set "JAR="
for %%F in (build\libs\zlorbies-spotify-*.jar) do (
  echo %%~nxF | findstr /i "sources" >nul || set "JAR=%%F"
)
if not defined JAR (
  echo ==========================================================
  echo   Gradle said OK but no mod jar was found in build\libs.
  echo   Send Claude the full text above.
  echo ==========================================================
  dir build\libs
  pause
  exit /b 1
)

mkdir "dist" >nul 2>&1
copy /y "!JAR!" "dist\" >nul
echo ==========================================================
echo   BUILD SUCCESSFUL
echo   Your mod is in the dist folder: !JAR:build\libs\=!
echo   Put it in your mods folder (delete any older
echo   zlorbies-spotify jar there first).
echo ==========================================================
start "" "%~dp0dist"
pause
exit /b 0

:find
for %%P in ("%ProgramFiles%\Eclipse Adoptium" "%LOCALAPPDATA%\Programs\Eclipse Adoptium" "%ProgramFiles%\Java" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Amazon Corretto" "%ProgramFiles%\Zulu" "%ProgramFiles%\BellSoft") do (
  for /d %%D in ("%%~P\jdk-21*" "%%~P\jdk21*" "%%~P\*jdk*21*") do (
    if exist "%%~D\bin\javac.exe" set "JDK=%%~D"
  )
)
if not defined JDK if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
  "%JAVA_HOME%\bin\java" -version 2>&1 | findstr /c:"21." >nul && set "JDK=%JAVA_HOME%"
)
goto :eof
