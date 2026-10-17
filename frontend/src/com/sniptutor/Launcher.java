package com.sniptutor;

/**
 * Standalone entry-point wrapper for packaging SnipTutor as a native Windows .exe
 * without requiring module-path arguments at launch time.
 */
public class Launcher {
    public static void main(String[] args) {
        SnipTutorApp.main(args);
    }
}
