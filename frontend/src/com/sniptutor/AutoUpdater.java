package com.sniptutor;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Over-The-Air (OTA) Auto-Updater for the SnipTutor Windows Desktop App.
 * Automatically checks GitHub (and Render fallback) for a newer SnipTutor.jar,
 * verifies its SHA-256 checksum, and seamlessly updates the local installation.
 */
public class AutoUpdater {

    private static final String GITHUB_RAW_BASE = "https://raw.githubusercontent.com/vssanjay2005-lab/SnipTutor/main/app_update";
    private static volatile boolean shutdownHookRegistered = false;

    /**
     * Runs at startup in Launcher.main() BEFORE JavaFX initializes.
     * If a staged update or a quick 2.5s GitHub check finds a newer SnipTutor.jar,
     * swaps the JAR via a helper script and relaunches SnipTutor.exe immediately.
     */
    public static void checkAndUpdateOnStartup(String[] args) {
        try {
            for (String arg : args) {
                if ("--updated".equals(arg)) {
                    // Already relaunched after an update; skip startup check to prevent any loop
                    return;
                }
            }

            Path jarPath = getRunningJarPath();
            if (jarPath == null) {
                return; // Running from IDE / unpacked classes directory
            }

            Path stagedJar = jarPath.resolveSibling("SnipTutor.jar.new");
            String localSha = sha256Hex(Files.readAllBytes(jarPath));

            // 1. If a valid staged JAR was already downloaded in a prior session, apply it immediately
            if (Files.exists(stagedJar) && Files.size(stagedJar) > 10_000) {
                String stagedSha = sha256Hex(Files.readAllBytes(stagedJar));
                if (stagedSha != null && !stagedSha.equalsIgnoreCase(localSha)) {
                    applyStagedUpdateAndRelaunch(jarPath, stagedJar);
                    return;
                } else {
                    try { Files.deleteIfExists(stagedJar); } catch (Exception ignored) {}
                }
            }

            // 2. Quick startup check against GitHub Raw CDN (timeout 2.5s so launch is never delayed)
            if (downloadNewJarIfAvailable(jarPath, localSha, Duration.ofMillis(2500))) {
                if (Files.exists(stagedJar)) {
                    applyStagedUpdateAndRelaunch(jarPath, stagedJar);
                }
            }
        } catch (Exception ignored) {
            // Never block app launch if offline or GitHub is unreachable
        }
    }

    /**
     * Runs asynchronously in the background while the app is open.
     * If a new update is pushed to GitHub while the user has SnipTutor open,
     * stages SnipTutor.jar.new and installs a shutdown hook to swap it on exit.
     */
    public static void checkInBackgroundAsync() {
        Thread updaterThread = new Thread(() -> {
            try {
                Path jarPath = getRunningJarPath();
                if (jarPath == null) return;

                String localSha = sha256Hex(Files.readAllBytes(jarPath));
                if (downloadNewJarIfAvailable(jarPath, localSha, Duration.ofSeconds(10))) {
                    Path stagedJar = jarPath.resolveSibling("SnipTutor.jar.new");
                    if (Files.exists(stagedJar)) {
                        registerSwapOnShutdown(jarPath, stagedJar);
                    }
                }
            } catch (Exception ignored) {}
        }, "SnipTutor-AutoUpdater");
        updaterThread.setDaemon(true);
        updaterThread.start();
    }

    private static Path getRunningJarPath() {
        try {
            URI uri = Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri);
            if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                return path.toAbsolutePath();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean downloadNewJarIfAvailable(Path jarPath, String localSha, Duration timeout) {
        if (localSha == null) return false;
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            long cacheBuster = System.currentTimeMillis();
            String versionUrl = GITHUB_RAW_BASE + "/version.txt?t=" + cacheBuster;

            HttpRequest verReq = HttpRequest.newBuilder()
                .uri(URI.create(versionUrl))
                .timeout(timeout)
                .GET()
                .build();

            HttpResponse<String> verRes = client.send(verReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (verRes.statusCode() != 200 || verRes.body() == null) {
                return false;
            }

            String remoteSha = verRes.body().trim().split("\\s+")[0].toLowerCase();
            if (!remoteSha.matches("^[0-9a-f]{64}$")) {
                return false;
            }

            if (remoteSha.equalsIgnoreCase(localSha)) {
                return false; // Already on the latest version!
            }

            // Newer version detected! Download SnipTutor.jar
            String jarUrl = GITHUB_RAW_BASE + "/SnipTutor.jar?t=" + cacheBuster;
            HttpRequest jarReq = HttpRequest.newBuilder()
                .uri(URI.create(jarUrl))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

            HttpResponse<byte[]> jarRes = client.send(jarReq, HttpResponse.BodyHandlers.ofByteArray());
            if (jarRes.statusCode() != 200 || jarRes.body() == null || jarRes.body().length < 10_000) {
                return false;
            }

            byte[] newBytes = jarRes.body();
            String downloadedSha = sha256Hex(newBytes);
            if (!remoteSha.equalsIgnoreCase(downloadedSha)) {
                return false; // Checksum mismatch; abort
            }

            Path stagedJar = jarPath.resolveSibling("SnipTutor.jar.new");
            Files.write(stagedJar, newBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void applyStagedUpdateAndRelaunch(Path jarPath, Path stagedJar) {
        try {
            Path appDir = jarPath.getParent();
            Path rootDir = appDir != null ? appDir.getParent() : null;
            Path exePath = (rootDir != null) ? rootDir.resolve("SnipTutor.exe") : null;

            String jarStr = jarPath.toAbsolutePath().toString().replace("'", "''");
            String stagedStr = stagedJar.toAbsolutePath().toString().replace("'", "''");

            StringBuilder psCmd = new StringBuilder();
            psCmd.append("Start-Sleep -Milliseconds 700; ");
            psCmd.append("for ($i=0; $i -lt 10; $i++) { ");
            psCmd.append("  try { Move-Item -Force -LiteralPath '").append(stagedStr).append("' -Destination '").append(jarStr).append("'; break } ");
            psCmd.append("  catch { Start-Sleep -Milliseconds 400 } ");
            psCmd.append("}; ");

            if (exePath != null && Files.exists(exePath)) {
                String exeStr = exePath.toAbsolutePath().toString().replace("'", "''");
                psCmd.append("Start-Process -FilePath '").append(exeStr).append("' -ArgumentList '--updated'");
            }

            new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psCmd.toString())
                .directory(new File(System.getProperty("user.home")))
                .start();

            System.exit(0);
        } catch (Exception ignored) {}
    }

    private static synchronized void registerSwapOnShutdown(Path jarPath, Path stagedJar) {
        if (shutdownHookRegistered) return;
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                String jarStr = jarPath.toAbsolutePath().toString().replace("'", "''");
                String stagedStr = stagedJar.toAbsolutePath().toString().replace("'", "''");
                String psCmd = "Start-Sleep -Milliseconds 700; " +
                    "for ($i=0; $i -lt 10; $i++) { " +
                    "  try { Move-Item -Force -LiteralPath '" + stagedStr + "' -Destination '" + jarStr + "'; break } " +
                    "  catch { Start-Sleep -Milliseconds 400 } " +
                    "}";
                new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psCmd)
                    .directory(new File(System.getProperty("user.home")))
                    .start();
            } catch (Exception ignored) {}
        }));
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
