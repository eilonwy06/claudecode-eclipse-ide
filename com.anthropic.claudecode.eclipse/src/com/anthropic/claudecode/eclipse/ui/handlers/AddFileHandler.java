package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.server.HttpSseServer;
import com.google.gson.JsonObject;

public class AddFileHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            if (editor == null) return null;

            IEditorInput input = editor.getEditorInput();
            String filePath = null;

            if (input instanceof IFileEditorInput fileInput) {
                IFile file = fileInput.getFile();
                filePath = file.getLocation() != null ? file.getLocation().toOSString() : null;
            } else if (input instanceof org.eclipse.ui.IURIEditorInput uriInput) {
                filePath = uriInput.getURI().getPath();
            }

            if (filePath == null) return null;

            HttpSseServer server = Activator.getDefault().getHttpSseServer();
            if (server != null && server.hasConnectedClients()) {
                JsonObject notification = new JsonObject();
                notification.addProperty("jsonrpc", "2.0");
                notification.addProperty("method", "notifications/fileAdded");

                JsonObject params = new JsonObject();
                params.addProperty("filePath", filePath);
                notification.add("params", params);

                server.broadcast(notification.toString());
                Activator.log("File added to Claude context: " + filePath);
            }
        } catch (Exception e) {
            Activator.logError("Failed to add file to context", e);
        }
        return null;
    }
}
