package com.anthropic.claudecode.eclipse.tools;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IURIEditorInput;

import com.google.gson.JsonObject;

final class EditorUtils {

    private EditorUtils() {}

    static String getFilePath(IEditorInput input) {
        if (input instanceof IFileEditorInput fileInput) {
            IFile file = fileInput.getFile();
            return file.getLocation() != null ? file.getLocation().toOSString() : null;
        }
        if (input instanceof IURIEditorInput uriInput) {
            return uriInput.getURI().getPath();
        }
        return null;
    }

    /**
     * Get a string param trying snake_case first, then camelCase.
     * Claude Code's hardcoded param names may use either convention.
     */
    static String getString(JsonObject params, String snakeCase, String camelCase) {
        if (params.has(snakeCase)) return params.get(snakeCase).getAsString();
        if (params.has(camelCase)) return params.get(camelCase).getAsString();
        return null;
    }

    static String requireString(JsonObject params, String snakeCase, String camelCase) {
        String val = getString(params, snakeCase, camelCase);
        if (val == null) throw new IllegalArgumentException("Missing required param: " + snakeCase + " or " + camelCase);
        return val;
    }

    static int getInt(JsonObject params, String snakeCase, String camelCase, int defaultVal) {
        if (params.has(snakeCase)) return params.get(snakeCase).getAsInt();
        if (params.has(camelCase)) return params.get(camelCase).getAsInt();
        return defaultVal;
    }
}
