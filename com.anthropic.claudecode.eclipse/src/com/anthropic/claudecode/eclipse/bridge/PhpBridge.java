package com.anthropic.claudecode.eclipse.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;

public final class PhpBridge {

    private static final int PORT_A = 19801;
    private static final int PORT_B = 19802;
    private static final long STARTUP_TIMEOUT_MS = 5000;

    private Process process;
    private Socket socketB;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean overridden = new AtomicBoolean(false);
    private Consumer<byte[]> dataCallback;
    private Path extractDir;
    private String phpMessage;

    public PhpBridge() {}

    public synchronized boolean start(Consumer<byte[]> dataCallback) {
        if (running.get()) {
            return true;
        }
        this.dataCallback = dataCallback;

        try {
            Path binary;
            // On macOS, try Homebrew PHP paths (Apple removed /usr/bin/php in Monterey)
            if (isMacOS()) {
                Path homebrewArm = Path.of("/opt/homebrew/bin/php");
                Path homebrewIntel = Path.of("/usr/local/bin/php");
                if (Files.isExecutable(homebrewArm)) {
                    binary = homebrewArm;
                    debugLog("[Bridge] Using Homebrew PHP (ARM): " + binary);
                } else if (Files.isExecutable(homebrewIntel)) {
                    binary = homebrewIntel;
                    debugLog("[Bridge] Using Homebrew PHP (Intel): " + binary);
                } else {
                    debugLog("[Bridge] No Homebrew PHP found, using bundled");
                    binary = extractBinary();
                    // Clear quarantine attribute on macOS
                    try {
                        new ProcessBuilder("/usr/bin/xattr", "-cr", binary.toAbsolutePath().toString())
                            .start().waitFor();
                        debugLog("[Bridge] Cleared quarantine attributes");
                    } catch (Exception e) {
                        debugLog("[Bridge] Could not clear xattr: " + e.getMessage());
                    }
                }
            } else {
                debugLog("[Bridge] Extracting binary...");
                binary = extractBinary();
            }
            debugLog("[Bridge] Binary: " + binary.toAbsolutePath());
            debugLog("[Bridge] Binary exists: " + Files.exists(binary));
            debugLog("[Bridge] Binary executable: " + Files.isExecutable(binary));

            Path script = extractScript();
            debugLog("[Bridge] Script: " + script.toAbsolutePath());

            // Use file-based ready signal (works around macOS pipe buffering)
            Path readyFile = Files.createTempFile("cb_ready_", ".txt");
            readyFile.toFile().deleteOnExit();
            Files.deleteIfExists(readyFile); // PHP will create it
            debugLog("[Bridge] Ready file: " + readyFile.toAbsolutePath());

            int[] ports = new int[2];

            String osName = System.getProperty("os.name", "");
            debugLog("[Bridge] os.name = " + osName);

            ProcessBuilder pb;
            if (!isWindows()) {
                // Unix (macOS/Linux): spawn through shell to get proper environment
                String cmd = String.format("'%s' '%s' %d %d '%s'",
                    binary.toAbsolutePath().toString(),
                    script.toAbsolutePath().toString(),
                    PORT_A, PORT_B,
                    readyFile.toAbsolutePath().toString());
                pb = new ProcessBuilder("/bin/sh", "-c", cmd);
                debugLog("[Bridge] Shell command: " + cmd);
            } else {
                pb = new ProcessBuilder(
                    binary.toAbsolutePath().toString(),
                    script.toAbsolutePath().toString(),
                    String.valueOf(PORT_A),
                    String.valueOf(PORT_B),
                    readyFile.toAbsolutePath().toString()
                );
            }
            pb.redirectErrorStream(false);
            debugLog("[Bridge] Starting process...");
            process = pb.start();
            debugLog("[Bridge] Process started, waiting for READY...");

            // Drain stdout/stderr to prevent blocking
            Thread stdoutDrain = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        System.out.println("[PhpBridge STDOUT] " + line);
                    }
                } catch (IOException ignored) {}
            }, "bridge-stdout");
            stdoutDrain.setDaemon(true);
            stdoutDrain.start();

            Thread stderrDrain = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        System.err.println("[PhpBridge STDERR] " + line);
                    }
                } catch (IOException ignored) {}
            }, "bridge-stderr");
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            // Poll for ready file
            long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
            boolean gotReady = false;
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    debugErr("[Bridge] Process died early with exit code: " + exitCode);
                    stop();
                    return false;
                }
                if (Files.exists(readyFile)) {
                    String content = Files.readString(readyFile).trim();
                    String[] lines = content.split("\n");
                    String firstLine = lines[0].trim();
                    if (firstLine.equals("STARTED")) {
                        debugLog("[Bridge] Script started, waiting for socket binding...");
                    } else if (firstLine.startsWith("READY ")) {
                        debugLog("[Bridge] Ready file content: " + firstLine);
                        String[] parts = firstLine.split(" ");
                        if (parts.length == 3) {
                            ports[0] = Integer.parseInt(parts[1]);
                            ports[1] = Integer.parseInt(parts[2]);
                            gotReady = true;
                            // Log PHP confirmation message if present
                            if (lines.length > 1) {
                                phpMessage = lines[1].trim();
                            }
                            break;
                        }
                    } else {
                        debugLog("[Bridge] Unexpected ready file content: " + content);
                    }
                }
                Thread.sleep(100);
            }
            if (!gotReady) {
                debugErr("[Bridge] Timeout waiting for READY signal");
                debugErr("[Bridge] Process still alive at timeout: " + process.isAlive());
                debugErr("[Bridge] Ready file exists: " + Files.exists(readyFile));
                stop();
                // On macOS, set override mode instead of failing completely
                if (isMacOS()) {
                    debugLog("[Bridge] macOS detected - enabling direct protocol override");
                    overridden.set(true);
                }
                return false;
            }
            debugLog("[Bridge] Got READY, connecting to port " + ports[1]);

            socketB = new Socket("127.0.0.1", ports[1]);
            running.set(true);

            readerThread = new Thread(this::readLoop, "bridge-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            return true;

        } catch (Exception e) {
            debugErr("[Bridge] Failed to start: " + e.getMessage());
            e.printStackTrace();
            stop();
            return false;
        }
    }

    public synchronized void send(byte[] data) {
        if (!running.get() || socketB == null) {
            return;
        }
        try {
            OutputStream out = socketB.getOutputStream();
            out.write(data);
            out.flush();
        } catch (IOException e) {
            stop();
        }
    }

    public void send(String text) {
        send(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public synchronized void stop() {
        running.set(false);

        if (socketB != null) {
            try { socketB.close(); } catch (IOException ignored) {}
            socketB = null;
        }

        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }

        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    public boolean isRunning() {
        return running.get() && process != null && process.isAlive();
    }

    public boolean isOverridden() {
        return overridden.get();
    }

    public int getPortA() {
        return PORT_A;
    }

    public int getPortB() {
        return PORT_B;
    }

    public String getPhpMessage() {
        return phpMessage;
    }

    private void readLoop() {
        try {
            InputStream in = socketB.getInputStream();
            byte[] buf = new byte[65536];
            int n;
            while (running.get() && (n = in.read(buf)) != -1) {
                if (dataCallback != null && n > 0) {
                    byte[] data = new byte[n];
                    System.arraycopy(buf, 0, data, 0, n);
                    dataCallback.accept(data);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                stop();
            }
        }
    }

    private Path extractBinary() throws IOException {
        String basePath = binaryBasePath();
        if (basePath == null) {
            throw new IOException("Unsupported platform");
        }

        extractDir = Files.createTempDirectory("cb_");

        String manifestPath = basePath + "/manifest.txt";
        try (InputStream manifestIn = getClass().getResourceAsStream(manifestPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(manifestIn))) {
            if (manifestIn == null) {
                throw new IOException("Manifest not found: " + manifestPath);
            }
            String fileName;
            while ((fileName = reader.readLine()) != null) {
                fileName = fileName.trim();
                if (fileName.isEmpty()) continue;

                String filePath = basePath + "/" + fileName;
                try (InputStream fileIn = getClass().getResourceAsStream(filePath)) {
                    if (fileIn == null) continue;
                    Path target = extractDir.resolve(fileName);
                    Files.copy(fileIn, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        Path binary = extractDir.resolve(isWindows() ? "php.exe" : "php");
        if (!Files.exists(binary)) {
            throw new IOException("Binary not extracted");
        }

        if (!isWindows()) {
            try {
                Files.setPosixFilePermissions(binary, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                ));
            } catch (UnsupportedOperationException ignored) {}
        }

        return binary;
    }

    private Path extractScript() throws IOException {
        String resourcePath = "/scripts/bridge.php";
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Script not found: " + resourcePath);
            }
            Path tmp = Files.createTempFile("cs_", ".php");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    private String binaryBasePath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dir = (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : "x86_64";

        if (os.contains("win")) {
            return "/runtime/windows/" + dir;
        }
        if (os.contains("linux")) {
            return "/runtime/linux/" + dir;
        }
        if (os.contains("mac")) {
            return "/runtime/macos/" + dir;
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private boolean isDebugMode() {
        try {
            return Activator.getDefault().getPreferenceStore().getBoolean(Constants.PREF_DEBUG_MODE);
        } catch (Exception e) {
            return false;
        }
    }

    private void debugLog(String message) {
        if (isDebugMode()) {
            System.out.println(message);
        }
    }

    private void debugErr(String message) {
        if (isDebugMode()) {
            System.err.println(message);
        }
    }
}
