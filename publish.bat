@echo off
rem Build "Clock Denanex" and upload it (target in publish.properties). Turn VPN off first.
rem Gradle 8.7 runs only on Java 17..21. With Java 22+ (e.g. 25) the build fails with just
rem "What went wrong: 25.0.3", so every Java found is checked and only 17..21 is used.
setlocal EnableDelayedExpansion
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
call gradlew.bat publishApk
echo.
echo ===== DONE (if you see BUILD SUCCESSFUL above, it worked) =====
pause
exit /b 0

rem ---- :try "folder" -> sets JH if folder\bin\java.exe exists and is Java 17..21 ----
:try
if "%~1"=="" exit /b 0
if not exist "%~1\bin\java.exe" exit /b 0
"%~1\bin\java.exe" -version 2>&1 | findstr /r /c:"version .1[7-9][^0-9]" /c:"version .2[01][^0-9]" >nul
if not errorlevel 1 set "JH=%~1"
exit /b 0
