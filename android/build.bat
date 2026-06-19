@echo off
chcp 65001 >nul
title 老人定位 - APK 构建工具

echo.
echo === 老人定位监控 - APK 构建 ===
echo.

:: Check Java
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [错误] 未找到 Java，请安装 JDK 17+
    echo        下载: https://adoptium.net/
    pause
    exit /b 1
)
echo [OK] Java 已安装
java -version 2>&1 | findstr "version" 

:: Check ANDROID_HOME
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
    ) else if exist "C:\Program Files\Android\Sdk" (
        set ANDROID_HOME=C:\Program Files\Android\Sdk
    ) else if exist "%USERPROFILE%\Android\Sdk" (
        set ANDROID_HOME=%USERPROFILE%\Android\Sdk
    ) else (
        echo [错误] 未找到 Android SDK
        echo        请安装 Android Studio: https://developer.android.com/studio
        echo        或设置 ANDROID_HOME 环境变量指向 SDK 目录
        pause
        exit /b 1
    )
)
echo [OK] Android SDK: %ANDROID_HOME%

:: Check SDK components
if not exist "%ANDROID_HOME%\platforms\android-34" (
    echo [安装] 正在安装 Android SDK platform 34...
    call "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager" "platforms;android-34" "build-tools;34.0.0"
)

:: Create local.properties
echo sdk.dir=%ANDROID_HOME% > local.properties
echo [OK] local.properties 已创建

:: Build
echo.
echo [构建] 开始编译 APK...
call gradlew assembleDebug --no-daemon

if %ERRORLEVEL% equ 0 (
    echo.
    echo [成功] APK 已生成!
    for /r app\build\outputs\apk\debug %%f in (*.apk) do (
        echo         %%~ff
        echo         大小: %%~zf 字节
    )
) else (
    echo.
    echo [失败] 构建出错，请检查上面的错误信息
)

echo.
pause
