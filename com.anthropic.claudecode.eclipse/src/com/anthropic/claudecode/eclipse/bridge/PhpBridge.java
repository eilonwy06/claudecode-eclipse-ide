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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class PhpBridge {

    private static final int PORT_A = 19801;
    private static final int PORT_B = 19802;
    private static final long STARTUP_TIMEOUT_MS = 5000;

    private Process process;
    private Socket socketB;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Consumer<byte[]> dataCallback;
    private Path extractDir;

    public PhpBridge() {}

    public synchronized boolean start(Consumer<byte[]> dataCallback) {
        if (running.get()) {
            return true;
        }
        this.dataCallback = dataCallback;

        try {
            System.out.println("[PhpBridge] Extracting binary...");
            Path binary = extractBinary();
            System.out.println("[PhpBridge] Binary: " + binary.toAbsolutePath());
            System.out.println("[PhpBridge] Binary exists: " + Files.exists(binary));
            System.out.println("[PhpBridge] Binary executable: " + Files.isExecutable(binary));

            Path script = extractScript();
            System.out.println("[PhpBridge] Script: " + script.toAbsolutePath());

            CountDownLatch readyLatch = new CountDownLatch(1);
            int[] ports = new int[2];

            ProcessBuilder pb = new ProcessBuilder(
                binary.toAbsolutePath().toString(),
                script.toAbsolutePath().toString(),
                String.valueOf(PORT_A),
                String.valueOf(PORT_B)
            );
            pb.redirectErrorStream(false);
            System.out.println("[PhpBridge] Starting process...");
            process = pb.start();
            System.out.println("[PhpBridge] Process started, waiting for READY...");

            Thread startupReader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        System.out.println("[PhpBridge STDOUT] " + line);
                        if (line.startsWith("READY ")) {
                            String[] parts = line.split(" ");
                            if (parts.length == 3) {
                                ports[0] = Integer.parseInt(parts[1]);
                                ports[1] = Integer.parseInt(parts[2]);
                                readyLatch.countDown();
                            }
                        }
                    }
                    System.out.println("[PhpBridge] STDOUT stream ended");
                } catch (IOException e) {
                    System.err.println("[PhpBridge] Error reading stdout: " + e.getMessage());
                }
            }, "bridge-startup");
            startupReader.setDaemon(true);
            startupReader.start();

            Thread stderrDrain = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        System.err.println("[PhpBridge STDERR] " + line);
                        // Also check stderr for READY (diagnostic)
                        if (line.startsWith("READY_STDERR ")) {
                            System.out.println("[PhpBridge] Got READY on STDERR - stdout buffering issue confirmed!");
                            String[] parts = line.split(" ");
                            if (parts.length == 3 && readyLatch.getCount() > 0) {
                                ports[0] = Integer.parseInt(parts[1]);
                                ports[1] = Integer.parseInt(parts[2]);
                                readyLatch.countDown();
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }, "bridge-stderr");
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            // Check every 500ms if process died early
            long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (readyLatch.await(500, TimeUnit.MILLISECONDS)) {
                    break; // got READY
                }
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    System.err.println("[PhpBridge] Process died early with exit code: " + exitCode);
                    stop();
                    return false;
                }
                System.out.println("[PhpBridge] Still waiting... process alive: " + process.isAlive());
            }
            if (readyLatch.getCount() > 0) {
                System.err.println("[PhpBridge] Timeout waiting for READY signal");
                System.err.println("[PhpBridge] Process still alive at timeout: " + process.isAlive());
                stop();
                return false;
            }
            System.out.println("[PhpBridge] Got READY, connecting to port " + ports[1]);

            socketB = new Socket("127.0.0.1", ports[1]);
            running.set(true);

            readerThread = new Thread(this::readLoop, "bridge-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            return true;

        } catch (Exception e) {
            System.err.println("[PhpBridge] Failed to start: " + e.getMessage());
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

    public int getPortA() {
        return PORT_A;
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
}
