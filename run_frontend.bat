@echo off
echo Compiling and starting SnipTutor JavaFX Frontend with Global Hotkey Support...

if not defined PATH_TO_FX (
    if exist "C:\Users\acer\Downloads\openjfx-26.0.2_windows-x64_bin-sdk\javafx-sdk-26.0.2\lib" (
        set "PATH_TO_FX=C:\Users\acer\Downloads\openjfx-26.0.2_windows-x64_bin-sdk\javafx-sdk-26.0.2\lib"
    ) else if exist "lib\javafx\lib" (
        set "PATH_TO_FX=lib\javafx\lib"
    )
)

if not exist "frontend\bin" mkdir "frontend\bin"

javac -encoding UTF-8 -cp "lib/jnativehook-2.2.2.jar" --module-path "%PATH_TO_FX%" --add-modules javafx.controls,javafx.fxml -d frontend\bin frontend\src\com\sniptutor\*.java
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed. Ensure Java 21+ and PATH_TO_FX are configured.
    pause
    exit /b %ERRORLEVEL%
)

copy /Y frontend\src\com\sniptutor\chat.fxml frontend\bin\com\sniptutor\chat.fxml >nul
copy /Y frontend\src\com\sniptutor\styles.css frontend\bin\com\sniptutor\styles.css >nul

java --enable-native-access=ALL-UNNAMED --module-path "%PATH_TO_FX%" --add-modules javafx.controls,javafx.fxml -cp "frontend\bin;lib\jnativehook-2.2.2.jar" com.sniptutor.SnipTutorApp
pause
