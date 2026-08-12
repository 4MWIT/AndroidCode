@echo off
setlocal

set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk

if not exist "%APK%" (
  echo APK not found: %APK%
  echo Build the project first.
  exit /b 1
)

adb devices
if errorlevel 1 exit /b 1

adb install -r -t "%APK%"
if errorlevel 1 exit /b 1

adb shell am start -n com.tom.rv2ide/com.example.aicode.MainActivity
if errorlevel 1 exit /b 1

echo Done.
