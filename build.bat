@echo off
chcp 65001 >nul
echo.
echo  ========================================
echo   AniMurglar Build
echo  ========================================
echo.
echo  [1] Build app (no ProGuard)
echo  [2] Build ZIP (no ProGuard)
echo  [3] Build ZIP (with ProGuard)
echo.
set /p choice="Select: "

if "%choice%"=="1" goto build-app
if "%choice%"=="2" goto build-zip
if "%choice%"=="3" goto build-zip-proguard
echo Invalid choice.
pause
exit /b 1

:build-app
echo.
echo Building AniMurglar (no ProGuard)...
call gradlew.bat createDistributable
if %errorlevel% neq 0 (
    echo.
    echo Build failed.
    pause
    exit /b %errorlevel%
)
echo.
echo Build succeeded. Opening output folder...
explorer "build\compose\binaries\main\app\AniMurglar"
goto end

:build-zip
echo.
echo Building AniMurglar ZIP (no ProGuard)...
call gradlew.bat zipAppImage
if %errorlevel% neq 0 (
    echo.
    echo Build failed.
    pause
    exit /b %errorlevel%
)
echo.
echo Build succeeded. Opening artifacts folder...
explorer "build\artifacts"
goto end

:build-zip-proguard
echo.
echo Building AniMurglar ZIP (with ProGuard)...
call gradlew.bat zipReleaseAppImage
if %errorlevel% neq 0 (
    echo.
    echo Build failed.
    pause
    exit /b %errorlevel%
)
echo.
echo Build succeeded. Opening artifacts folder...
explorer "build\artifacts"
goto end

:end
echo.
pause
