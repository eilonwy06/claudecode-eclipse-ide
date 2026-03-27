package com.anthropic.claudecode.eclipse.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.anthropic.claudecode.eclipse.Activator;

public class SseClient {

    private static final long KEEPALIVE_INTERVAL_SEC = 15;

    private final String id;
    private final String sessionId;
    private final OutputStream out;
    private final BlockingQueue<String> eventQueue = new LinkedBlockingQueue<>();
    private volatile boolean closed = false;

    public SseClient(String id, String sessionId, OutputStream out) {
        this.id = id;
        this.sessionId = sessionId;
        this.out = out;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void sendEvent(String eventType, String data) {
        if (closed) return;
        String formatted = "event: " + eventType + "\ndata: " + data + "\n\n";
        eventQueue.offer(formatted);
    }

    public void sendText(String jsonRpcMessage) {
        sendEvent("message", jsonRpcMessage);
    }

    /**
     * Blocks on the SSE handler thread, draining the event queue and writing to the output stream.
     * Sends keepalive comments when idle. Exits when closed or on write failure.
     */
    public void writeLoop() {
        try {
            while (!closed) {
                String event = eventQueue.poll(KEEPALIVE_INTERVAL_SEC, TimeUnit.SECONDS);
                if (closed) break;

                if (event != null) {
                    Activator.log("SSE flush [" + id.substring(0, 8) + "]: " + event.length() + " bytes");
                    out.write(event.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else {
                    out.write(":\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) {
                Activator.logError("SSE write error for client " + id, e);
            }
        } finally {
            closed = true;
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        eventQueue.clear();
    }

    public boolean isClosed() {
        return closed;
    }
}
