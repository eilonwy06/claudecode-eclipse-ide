package com.anthropic.claudecode.eclipse.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;

import com.anthropic.claudecode.eclipse.Activator;

/**
 * Locates / extracts the bundled PHP runtime. Shared by helpers that need to run
 * PHP scripts (e.g. {@link PhpHistory}). The relay's own extraction in
 * {@link PhpBridge} is intentionally left untouched.
 */
public final class PhpRuntime {

    private PhpRuntime() {}

    /**
     * Resolve a usable {@code php} executable: prefer a Homebrew PHP on macOS
     * (Apple removed {@code /usr/bin/php}), otherwise extract the bundled
     * per-platform runtime to a temp dir and return its binary.
     */
    public static Path resolveBinary() throws IOException {
        if (Activator.isMacOS()) {
            Path arm = Path.of("/opt/homebrew/bin/php");
            Path intel = Path.of("/usr/local/bin/php");
            if (Files.isExecutable(arm)) return arm;
            if (Files.isExecutable(intel)) return intel;
        }
        return extractBinary();
    }

    /** Extract the full bundled PHP runtime (per manifest) and return the binary path. */
    public static Path extractBinary() throws IOException {
        String basePath = binaryBasePath();
        if (basePath == null) throw new IOException("Unsupported platform for bundled PHP");

        Path extractDir = Files.createTempDirectory("cb_php_");
        String manifestPath = basePath + "/manifest.txt";
        try (InputStream manifestIn = PhpRuntime.class.getResourceAsStream(manifestPath)) {
            if (manifestIn == null) throw new IOException("Manifest not found: " + manifestPath);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(manifestIn))) {
                String fileName;
                while ((fileName = reader.readLine()) != null) {
                    fileName = fileName.trim();
                    if (fileName.isEmpty()) continue;
                    try (InputStream fileIn = PhpRuntime.class.getResourceAsStream(basePath + "/" + fileName)) {
                        if (fileIn == null) continue;
                        Files.copy(fileIn, extractDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        Path binary = extractDir.resolve(Activator.isWindows() ? "php.exe" : "php");
        if (!Files.exists(binary)) throw new IOException("PHP binary not extracted");
        if (!Activator.isWindows()) {
            try {
                Files.setPosixFilePermissions(binary, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {}
        }
        return binary;
    }

    /** Extract a bundled PHP script resource (e.g. {@code /scripts/history.php}) to a temp file. */
    public static Path extractScript(String resourcePath) throws IOException {
        try (InputStream in = PhpRuntime.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Script not found: " + resourcePath);
            Path tmp = Files.createTempFile("cb_", ".php");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    private static String binaryBasePath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dir = (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : "x86_64";
        if (os.contains("win"))   return "/runtime/windows/" + dir;
        if (os.contains("linux")) return "/runtime/linux/" + dir;
        if (os.contains("mac"))   return "/runtime/macos/" + dir;
        return null;
    }
}
