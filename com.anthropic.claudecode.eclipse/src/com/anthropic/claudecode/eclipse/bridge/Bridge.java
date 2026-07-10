package com.anthropic.claudecode.eclipse.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;

/**
 * The bridge relay, hosted by the native core. The relay shares the MCP
 * server's port range (portMin/portMax prefs) and binds the first two free
 * ports itself, so concurrent IDE instances can never end up on the same
 * relay pair; the assigned ports come back from bridgeStartRelay. Every peer
 * (this class on port B, the native side on port A) authenticates with a
 * per-session token before the relay wires it through, so no other local
 * process can attach to either side.
 */
public final class Bridge {

    private Socket socketB;
    private int portA;
    private int portB;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean overridden = new AtomicBoolean(false);
    private Consumer<byte[]> dataCallback;
    private String message;
    private String token;

    public Bridge() {}

    public synchronized boolean start(Consumer<byte[]> dataCallback) {
        if (running.get()) {
            return true;
        }
        this.dataCallback = dataCallback;

        try {
            // Per-session handshake secret. The relay refuses any peer that does
            // not present this on its first line. Generated in native code.
            this.token = NativeCore.bridgeGenerateToken();

            int portMin = Constants.PORT_RANGE_MIN;
            int portMax = Constants.PORT_RANGE_MAX;
            try {
                portMin = Activator.getDefault().getPreferenceStore().getInt(Constants.PREF_PORT_MIN);
                portMax = Activator.getDefault().getPreferenceStore().getInt(Constants.PREF_PORT_MAX);
            } catch (Exception ignored) {}
            debugLog("[Bridge] Port range: " + portMin + "-" + portMax);

            String ports = NativeCore.bridgeStartRelay(portMin, portMax, token);
            if (ports == null || ports.isEmpty()) {
                debugErr("[Bridge] Relay failed to start (no free port pair?)");
                stop();
                // On macOS, set override mode instead of failing completely.
                if (Activator.isMacOS()) {
                    debugLog("[Bridge] macOS detected - enabling direct protocol override");
                    overridden.set(true);
                }
                return false;
            }
            String[] parts = ports.split(" ");
            if (parts.length != 2) {
                debugErr("[Bridge] Unexpected relay reply: " + ports);
                stop();
                return false;
            }
            portA = Integer.parseInt(parts[0]);
            portB = Integer.parseInt(parts[1]);
            message = "Bridge connected.";
            debugLog("[Bridge] Relay up, connecting to port " + portB);

            socketB = new Socket("127.0.0.1", portB);
            // Authenticate this side to the relay before any data flows.
            OutputStream handshakeOut = socketB.getOutputStream();
            handshakeOut.write((token + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            handshakeOut.flush();
            running.set(true);

            readerThread = new Thread(this::readLoop, "bridge-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            return true;

        } catch (Exception e) {
            debugErr("[Bridge] Failed to start: " + e.getMessage());
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
        portA = 0;
        portB = 0;

        if (socketB != null) {
            try { socketB.close(); } catch (IOException ignored) {}
            socketB = null;
        }

        try { NativeCore.bridgeStopRelay(); } catch (Throwable ignored) {}

        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    public boolean isRunning() {
        boolean relayUp;
        try { relayUp = NativeCore.bridgeRelayIsRunning(); } catch (Throwable t) { relayUp = false; }
        return running.get() && relayUp;
    }

    public boolean isOverridden() {
        return overridden.get();
    }

    /** Port assigned by the relay for the native side; valid only after a successful start(). */
    public int getPortA() {
        return portA;
    }

    /** Port assigned by the relay for the Java side; valid only after a successful start(). */
    public int getPortB() {
        return portB;
    }

    /** Human-readable confirmation line for the log; valid only after a successful start(). */
    public String getMessage() {
        return message;
    }

    /** Handshake token for this relay session; valid only after a successful start(). */
    public String getToken() {
        return token;
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
