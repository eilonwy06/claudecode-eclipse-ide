package com.anthropic.claudecode.eclipse.server;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.google.gson.JsonArray;

/**
 * Thin Java wrapper over the Rust lock-file logic.
 *
 * Java is still responsible for querying the Eclipse workspace to collect
 * project paths; Rust handles the actual file I/O.
 */
public class LockFileManager {

    public void writeLockFile(int port, String authToken) {
        try {
            String workspaceRoot = ResourcesPlugin.getWorkspace().getRoot()
                    .getLocation().toOSString();

            // Collect open project paths (same as original implementation).
            JsonArray projectPaths = new JsonArray();
            projectPaths.add(workspaceRoot);
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen() && project.getLocation() != null) {
                    projectPaths.add(project.getLocation().toOSString());
                }
            }

            NativeCore.lockFileWrite(port, authToken, workspaceRoot, projectPaths.toString());
            Activator.log("Lock file written (Rust) for port " + port
                    + " workspace=" + workspaceRoot
                    + " projects=" + projectPaths.size());
        } catch (Exception e) {
            Activator.logError("Failed to write lock file", e);
        }
    }

    public void removeLockFile() {
        NativeCore.lockFileRemove();
        Activator.log("Lock file removed (Rust)");
    }
}
