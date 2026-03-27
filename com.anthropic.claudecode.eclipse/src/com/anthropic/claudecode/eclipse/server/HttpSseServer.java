package com.anthropic.claudecode.eclipse.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.mcp.McpMessageHandler;
import com.anthropic.claudecode.eclipse.mcp.McpToolRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class HttpSseServer {

    private final McpToolRegistry toolRegistry;
    private final int portMin;
    private final int portMax;
    private final String authToken;
    private final Map<String, SseClient> clients = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private HttpServer httpServer;
    private ExecutorService executor;
    private McpMessageHandler messageHandler;
    private int port;

    public HttpSseServer(McpToolRegistry toolRegistry, int portMin, int portMax) {
        this.toolRegistry = toolRegistry;
        this.portMin = portMin;
        this.portMax = portMax;
        this.authToken = UUID.randomUUID().toString();
    }

    public void start() {
        if (running.get()) return;

        try {
            httpServer = bindToAvailablePort();
            port = httpServer.getAddress().getPort();
            messageHandler = new McpMessageHandler(toolRegistry, this);

            executor = Executors.newVirtualThreadPerTaskExecutor();
            httpServer.setExecutor(executor);

            httpServer.createContext("/sse", this::handleSse);
            httpServer.createContext("/messages", this::handleMessages);

            httpServer.start();
            running.set(true);
            Activator.log("HTTP+SSE server listening on 127.0.0.1:" + port);
        } catch (IOException e) {
            Activator.logError("Failed to start HTTP+SSE server", e);
            throw new RuntimeException("Failed to start HTTP+SSE server", e);
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        clients.values().forEach(SseClient::close);
        clients.clear();

        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public void broadcast(String message) {
        clients.values().forEach(client -> client.sendText(message));
    }

    public void removeClient(String clientId) {
        SseClient removed = clients.remove(clientId);
        if (removed != null) {
            removed.close();
            Activator.log("Client disconnected: " + clientId + " (remaining: " + clients.size() + ")");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean hasConnectedClients() {
        return !clients.isEmpty();
    }

    public int getPort() {
        return port;
    }

    public String getAuthToken() {
        return authToken;
    }

    public int getClientCount() {
        return clients.size();
    }

    // --- SSE endpoint: GET /sse ---
    private void handleSse(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlainResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        Activator.log("Incoming SSE connection from: " + exchange.getRemoteAddress());
        logHeaders(exchange);

        String clientId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0); // chunked

        OutputStream out = exchange.getResponseBody();
        SseClient client = new SseClient(clientId, sessionId, out);
        clients.put(clientId, client);
        Activator.log("SSE client connected: " + clientId + " sessionId=" + sessionId + " (total: " + clients.size() + ")");

        // Send endpoint event so Claude knows where to POST messages
        String endpointUrl = "/messages?sessionId=" + sessionId;
        client.sendEvent("endpoint", endpointUrl);

        // Block on write loop — exits when client disconnects or server stops
        try {
            client.writeLoop();
        } finally {
            removeClient(clientId);
            try { exchange.close(); } catch (Exception ignored) {}
        }
    }

    // --- Messages endpoint: POST /messages ---
    private void handleMessages(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            sendPlainResponse(exchange, 204, "");
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlainResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String sessionId = extractQueryParam(exchange, "sessionId");
        SseClient client = findClientBySession(sessionId);
        if (client == null) {
            Activator.log("POST /messages — unknown sessionId: " + sessionId);
            sendPlainResponse(exchange, 400, "Unknown session");
            return;
        }

        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        Activator.log("POST /messages [sessionId=" + sessionId + "] body: " + body);

        // Return 202 immediately — response goes via SSE stream
        sendPlainResponse(exchange, 202, "Accepted");

        // Process the JSON-RPC message asynchronously
        messageHandler.handleMessage(client, body);
    }

    // --- Helpers ---
    private HttpServer bindToAvailablePort() throws IOException {
        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        for (int p = portMin; p <= portMax; p++) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(localhost, p), 0);
                return server;
            } catch (IOException ignored) {}
        }
        throw new IOException("No available port in range " + portMin + "-" + portMax);
    }

    private SseClient findClientBySession(String sessionId) {
        if (sessionId == null) return null;
        return clients.values().stream()
                .filter(c -> sessionId.equals(c.getSessionId()))
                .findFirst()
                .orElse(null);
    }

    private String extractQueryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void sendPlainResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(code, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void logHeaders(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().toString();
        Activator.log("  " + method + " " + path);
        exchange.getRequestHeaders().forEach((key, values) ->
            values.forEach(v -> Activator.log("  Header: " + key + ": " + v))
        );
    }
}
