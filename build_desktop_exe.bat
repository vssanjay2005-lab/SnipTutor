@echo off
echo =================================================================
echo   Building Standalone Windows Desktop App (SnipTutor.exe)
echo =================================================================

if not defined PATH_TO_FX (
    if exist "C:\Users\acer\Downloads\openjfx-26.0.2_windows-x64_bin-sdk\javafx-sdk-26.0.2\lib" (
        set "PATH_TO_FX=C:\Users\acer\Downloads\openjfx-26.0.2_windows-x64_bin-sdk\javafx-sdk-26.0.2\lib"
    ) else if exist "lib\javafx\lib" (
        set "PATH_TO_FX=lib\javafx\lib"
    )
)

if not exist "frontend\bin" mkdir "frontend\bin"
if not exist "build\input" mkdir "build\input"
if exist "dist\SnipTutor" rmdir /S /Q "dist\SnipTutor"
if not exist "dist" mkdir "dist"

echo [1/3] Compiling JavaFX source files...
javac -encoding UTF-8 -cp "lib/jnativehook-2.2.2.jar" --module-path "%PATH_TO_FX%" --add-modules javafx.controls,javafx.fxml -d frontend\bin frontend\src\com\sniptutor\*.java
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b %ERRORLEVEL%
)

copy /Y frontend\src\com\sniptutor\chat.fxml frontend\bin\com\sniptutor\chat.fxml >nul
copy /Y frontend\src\com\sniptutor\styles.css frontend\bin\com\sniptutor\styles.css >nul

echo [2/3] Packaging SnipTutor.jar and dependencies...
jar --create --file build\input\SnipTutor.jar --main-class com.sniptutor.Launcher -C frontend\bin .
copy /Y "lib\jnativehook-2.2.2.jar" "build\input\jnativehook-2.2.2.jar" >nul
copy /Y "%PATH_TO_FX%\*.jar" "build\input\" >nul
if exist "%PATH_TO_FX%\..\bin\*.dll" (
    copy /Y "%PATH_TO_FX%\..\bin\*.dll" "build\input\" >nul
)

echo [3/3] Creating native Windows Desktop App (dist\SnipTutor\SnipTutor.exe) via jpackage...
jpackage --type app-image ^
  --name SnipTutor ^
  --input build\input ^
  --main-jar SnipTutor.jar ^
  --main-class com.sniptutor.Launcher ^
  --java-options "--enable-native-access=ALL-UNNAMED" ^
  --dest dist

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] Standalone Windows Desktop App built at: dist\SnipTutor\SnipTutor.exe
    echo You can zip "dist\SnipTutor" and upload it to GitHub Releases for free download!
) else (
    echo [ERROR] jpackage failed. Ensure JDK 21+ is on your PATH.
)
pause
