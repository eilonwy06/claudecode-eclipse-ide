package com.anthropic.claudecode.eclipse.mcp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

public final class DeferredResponseManager {

    private static final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private DeferredResponseManager() {}

    public static CompletableFuture<JsonObject> create(String id) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        return future;
    }

    public static void complete(String id, JsonObject result) {
        CompletableFuture<JsonObject> future = pending.remove(id);
        if (future != null) {
            future.complete(result);
        }
    }

    public static void cancel(String id) {
        CompletableFuture<JsonObject> future = pending.remove(id);
        if (future != null) {
            future.cancel(true);
        }
    }

    public static boolean hasPending(String id) {
        return pending.containsKey(id);
    }
}
