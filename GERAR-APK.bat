@echo off
chcp 65001 >nul
cd /d "%~dp0"
title TRC TELAS - gerando APK
echo Gerando o APK do TRC TELAS... (a primeira vez demora alguns minutos)
del /q STATUS-*.txt 2>nul

rem --- Procura um Java 17 ou 21 (o Gradle 8 nao roda no Java 25) ---
set "JAVA_HOME="
for /d %%D in ("%USERPROFILE%\.jdks\*21*" "%USERPROFILE%\.jdks\*17*" "C:\Program Files\Java\jdk-21*" "C:\Program Files\Java\jdk-17*" "C:\Program Files\Eclipse Adoptium\jdk-21*" "C:\Program Files\Eclipse Adoptium\jdk-17*" "C:\Program Files\Microsoft\jdk-21*" "C:\Program Files\Microsoft\jdk-17*" "C:\Program Files\Zulu\zulu-21*" "C:\Program Files\Zulu\zulu-17*") do if not defined JAVA_HOME if exist "%%~D\bin\java.exe" set "JAVA_HOME=%%~D"

> build-log.txt (
  echo === JDKs encontrados ===
  dir /b "%USERPROFILE%\.jdks" 2^>nul
  dir /b "C:\Program Files\Java" 2^>nul
  dir /b "C:\Program Files\Eclipse Adoptium" 2^>nul
  dir /b "C:\Program Files\Microsoft" 2^>nul
  echo JAVA_HOME=%JAVA_HOME%
)
if not defined JAVA_HOME (
  echo SEM_JAVA>> build-log.txt
  echo semjava> STATUS-FALHOU.txt
  exit /b 1
)

set "SDK=%LOCALAPPDATA%\Android\Sdk"
echo sdk.dir=%SDK:\=\\%> local.properties

call gradlew.bat assembleDebug --no-daemon >> build-log.txt 2>&1
if exist "app\build\outputs\apk\debug\app-debug.apk" (
  copy /y "app\build\outputs\apk\debug\app-debug.apk" "TRC-TELAS.apk" >nul
  echo ok> STATUS-OK.txt
  echo APK pronto: TRC-TELAS.apk
) else (
  echo falhou> STATUS-FALHOU.txt
  echo Deu erro. Veja build-log.txt
)
