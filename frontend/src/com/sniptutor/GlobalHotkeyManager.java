package com.sniptutor;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import javafx.application.Platform;

import java.util.logging.Level;
import java.util.logging.Logger;

public class GlobalHotkeyManager implements NativeKeyListener {

    private final Runnable onHotkeyPressed;
    private boolean isCtrlPressed = false;
    private boolean isShiftPressed = false;

    public GlobalHotkeyManager(Runnable onHotkeyPressed) {
        this.onHotkeyPressed = onHotkeyPressed;
    }

    public void register() {
        try {
            Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.OFF);
            logger.setUseParentHandlers(false);

            if (!GlobalScreen.isNativeHookRegistered()) {
                GlobalScreen.registerNativeHook();
            }
            GlobalScreen.addNativeKeyListener(this);
        } catch (NativeHookException ex) {
            System.err.println("Failed to register global hotkey hook: " + ex.getMessage());
        }
    }

    public void unregister() {
        try {
            GlobalScreen.removeNativeKeyListener(this);
            if (GlobalScreen.isNativeHookRegistered()) {
                GlobalScreen.unregisterNativeHook();
            }
        } catch (NativeHookException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL) {
            isCtrlPressed = true;
        } else if (e.getKeyCode() == NativeKeyEvent.VC_SHIFT) {
            isShiftPressed = true;
        } else if (e.getKeyCode() == NativeKeyEvent.VC_SPACE) {
            if (isCtrlPressed && isShiftPressed) {
                if (onHotkeyPressed != null) {
                    Platform.runLater(onHotkeyPressed);
                }
            }
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL) {
            isCtrlPressed = false;
        } else if (e.getKeyCode() == NativeKeyEvent.VC_SHIFT) {
            isShiftPressed = false;
        }
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
    }
}