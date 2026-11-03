@echo off
rem Build "Clock Denanex" and upload it (target in publish.properties). Turn VPN off first.
rem Gradle 8.7 runs only on Java 17..21. With Java 22+ (e.g. 25) the build fails with just
rem "What went wrong: 25.0.3", so every Java found is checked and only 17..21 is used.
setlocal EnableDelayedExpansion
echo ===== publish.bat (version 2026-11-03c: Java 17-21 check, builds APK even without publish.properties) =====
set "JH="

rem 1) Android Studio's own Java (jbr) and JAVA_HOME
for %%P in ("C:\Program Files\Android\Android Studio\jbr" "C:\Program Files\Android\Android Studio\jre" "%LOCALAPPDATA%\Programs\Android Studio\jbr" "D:\Program Files\Android\Android Studio\jbr" "D:\Android\Android Studio\jbr" "D:\Android Studio\jbr" "C:\Android Studio\jbr" "%JAVA_HOME%") do (
  if not defined JH call :try "%%~P"
)

rem 2) JDKs downloaded by Android Studio (.jdks), other Android Studio folders, and common JDK folders
if not defined JH for /d %%D in ("%USERPROFILE%\.jdks\*" "C:\Program Files\Android\*" "D:\Program Files\Android\*" "C:\Program Files\Java\*" "C:\Program Files\Eclipse Adoptium\*" "C:\Program Files\Microsoft\*" "C:\Program Files\Zulu\*" "C:\Program Files\Amazon Corretto\*") do (
  if not defined JH call :try "%%~D"
  if not defined JH call :try "%%~D\jbr"
)

rem 3) Last resort: search the disks
if not defined JH (
  echo Searching for Java 17-21, please wait...
  for %%R in ("C:\Program Files" "C:\Program Files (x86)" "%LOCALAPPDATA%" "%USERPROFILE%" "D:\") do (
    if not defined JH for /f "delims=" %%F in ('dir /s /b "%%~R\java.exe" 2^>nul') do (
      if not defined JH call :try "%%~dpF.."
    )
  )
)

if not defined JH (
  echo.
  echo Java 17 to 21 was not found ^(Java 25 does not work with this project^).
  echo In Android Studio: File ^> Settings ^> Build, Execution, Deployment ^> Build Tools ^> Gradle
  echo ^> Gradle JDK ^> Download JDK... ^> Version 21 ^> Download. Then run publish.bat again.
  echo If it still fails, take a photo of this window and send it to Claude.
  pause
  exit /b 1
)
set "JAVA_HOME=!JH!"
echo Using Java: !JAVA_HOME!
"!JAVA_HOME!\bin\java.exe" -version
cd /d "%~dp0"
set "UPLOAD=1"
if not exist "publish.properties" (
  set "UPLOAD="
  echo.
  echo NOTE: publish.properties was not found in this folder, so the APK is only BUILT, not uploaded.
  echo       Put your publish.properties next to publish.bat to upload as well.
  echo.
)
if defined UPLOAD (
  call gradlew.bat publishApk
) else if exist "keystore.properties" (
  call gradlew.bat assembleRelease
) else (
  call gradlew.bat assembleDebug
)
if errorlevel 1 (
  echo.
  echo ===== FAILED: see the error above. Take a photo of this window and send it to Claude. =====
  pause
  exit /b 1
)
echo.
if defined UPLOAD (echo ===== SUCCESS: built and uploaded. =====) else (echo ===== SUCCESS: built ^(not uploaded^). =====)
rem The new APK: phones get it with "check for update" in the app, or copy it to the phone and install it.
set "APKDIR="
if exist "app\build\outputs\apk\release\aod.apk" set "APKDIR=app\build\outputs\apk\release"
if not defined APKDIR if exist "app\build\outputs\apk\debug\aod.apk" set "APKDIR=app\build\outputs\apk\debug"
set "APKNAME=aod.apk"
if not defined APKDIR if exist "app\build\outputs\apk\release\app-release.apk" (
  set "APKDIR=app\build\outputs\apk\release"
  set "APKNAME=app-release.apk"
)
if not defined APKDIR if exist "app\build\outputs\apk\debug\app-debug.apk" (
  set "APKDIR=app\build\outputs\apk\debug"
  set "APKNAME=app-debug.apk"
)
if defined APKDIR (
  echo The APK file: %~dp0!APKDIR!\!APKNAME!
  echo Copy it to the phone and install it. Opening that folder...
  start "" explorer /select,"%~dp0!APKDIR!\!APKNAME!"
)
pause
exit /b 0

rem ---- :try "folder" -> sets JH if folder\bin\java.exe exists and is Java 17..21 ----
:try
if "%~1"=="" exit /b 0
if not exist "%~1\bin\java.exe" exit /b 0
"%~1\bin\java.exe" -version 2>&1 | findstr /r /c:"version .1[7-9][^0-9]" /c:"version .2[01][^0-9]" >nul
if not errorlevel 1 set "JH=%~1"
exit /b 0
