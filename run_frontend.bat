@echo off
echo Compiling and starting SnipTutor JavaFX Frontend with Global Hotkey Support...
javac -cp "lib/jnativehook-2.2.2.jar" --module-path "C:\Users\acer\Downloads\openjfx-26.0.2_windows-x64_bin-sdk\javafx-sdk-26.0.2\lib" --add-modules javafx.controls,javafx.fxml -d frontend\bin frontend\src\com\sniptutor\*.java
copy /Y frontend\src\com\sniptutor\chat.fxml frontend\bin\com\sniptutor\chat.fxml
java --enable-native-access=ALL-UNNAMED --module-path "C:\Users\acer\Downloads\openjfx-26.0.2_windows-x64_bin-sdk\javafx-sdk-26.0.2\lib" --add-modules javafx.controls,javafx.fxml -cp "frontend\bin;lib\jnativehook-2.2.2.jar" com.sniptutor.SnipTutorApp
pause
