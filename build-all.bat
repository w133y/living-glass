@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo ============================================
echo   Living Glass - building all versions
echo ============================================
echo.

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found. Install Java 25 and run this again.
    pause
    exit /b 1
)

if exist out rmdir /s /q out
mkdir out

set VERSIONS=
for %%F in (versions\*.properties) do set VERSIONS=!VERSIONS! %%~nF

set OK=
set FAILED=
for %%V in (%VERSIONS%) do (
    echo.
    echo ---- Minecraft %%V ----
    call gradlew.bat clean build -PmcVersion=%%V --no-daemon
    if errorlevel 1 (
        echo [ERROR] Build for %%V failed, see the log above.
        set FAILED=!FAILED! %%V
    ) else (
        copy /y "build\libs\livingglass-*+%%V.jar" out\ >nul
        set OK=!OK! %%V
    )
)

echo.
echo ============================================
if defined OK echo Built:!OK!
if defined FAILED echo Failed:!FAILED!
echo Finished mods are in: %~dp0out
echo ============================================
pause