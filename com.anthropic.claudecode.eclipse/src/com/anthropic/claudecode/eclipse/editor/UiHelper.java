package com.anthropic.claudecode.eclipse.editor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

public final class UiHelper {

    private UiHelper() {}

    public static void asyncExec(Runnable runnable) {
        Display display = getDisplay();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(runnable);
        }
    }

    public static void syncExec(Runnable runnable) {
        Display display = getDisplay();
        if (display != null && !display.isDisposed()) {
            display.syncExec(runnable);
        }
    }

    public static <T> T syncCall(Supplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        syncExec(() -> result.set(supplier.get()));
        return result.get();
    }

    public static <T> CompletableFuture<T> asyncCall(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        asyncExec(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public static IWorkbenchPage getActivePage() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        if (window == null) {
            IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
            if (windows.length > 0) {
                window = windows[0];
            }
        }
        return window != null ? window.getActivePage() : null;
    }

    private static Display getDisplay() {
        try {
            return PlatformUI.getWorkbench().getDisplay();
        } catch (Exception e) {
            return Display.getDefault();
        }
    }
}
