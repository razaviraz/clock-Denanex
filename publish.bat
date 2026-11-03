@echo off
rem Build "Clock Denanex" and upload it (target in publish.properties). Turn VPN off first.
setlocal EnableDelayedExpansion
set "JH="
for %%P in ("C:\Program Files\Android\Android Studio\jbr" "C:\Program Files\Android\Android Studio\jre" "%LOCALAPPDATA%\Programs\Android Studio\jbr" "D:\Program Files\Android\Android Studio\jbr" "D:\Android\Android Studio\jbr" "D:\Android Studio\jbr" "C:\Android Studio\jbr") do (
  if not defined JH if exist "%%~P\bin\java.exe" set "JH=%%~P"
)
if not defined JH if exist "%USERPROFILE%\.jdks" for /d %%D in ("%USERPROFILE%\.jdks\*") do if not defined JH if exist "%%D\bin\java.exe" set "JH=%%D"
if not defined JH (
  echo Searching for Java, please wait...
  for %%R in ("C:\Program Files" "C:\Program Files (x86)" "%LOCALAPPDATA%" "D:\") do (
    if not defined JH for /f "delims=" %%F in ('dir /s /b "%%~R\java.exe" 2^>nul') do (
      if not defined JH set "JH=%%~dpF.."
    )
  )
)
if not defined JH (
  echo Java was not found. Take a photo of this window and send it to Claude.
  pause
  exit /b 1
)
set "JAVA_HOME=!JH!"
echo Using Java: !JAVA_HOME!
cd /d "%~dp0"
call gradlew.bat publishApk
echo.
echo ===== DONE (if you see BUILD SUCCESSFUL above, it worked) =====
pause
