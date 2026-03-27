package com.anthropic.claudecode.eclipse.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class LockFileManager {

    private Path lockFilePath;

    public void writeLockFile(int port, String authToken) {
        try {
            Path ideDir = getLockFileDirectory();
            Files.createDirectories(ideDir);

            lockFilePath = ideDir.resolve(port + ".lock");

            String workspaceRoot = ResourcesPlugin.getWorkspace().getRoot()
                    .getLocation().toOSString();

            // Collect workspace root + all open project paths
            JsonArray projectPaths = new JsonArray();
            projectPaths.add(workspaceRoot);
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen() && project.getLocation() != null) {
                    projectPaths.add(project.getLocation().toOSString());
                }
            }

            JsonObject lock = new JsonObject();
            lock.addProperty("port", port);
            lock.addProperty("authToken", authToken);
            lock.addProperty("version", Constants.LOCK_FILE_VERSION);
            lock.addProperty("ideName", Constants.IDE_NAME);
            lock.addProperty("pid", ProcessHandle.current().pid());
            lock.addProperty("workspaceFolder", workspaceRoot);
            lock.add("workspaceFolders", projectPaths);

            Files.writeString(lockFilePath, lock.toString());
            Activator.log("Lock file written: " + lockFilePath
                    + " (workspace: " + workspaceRoot
                    + ", projects: " + projectPaths.size() + ")");
        } catch (IOException e) {
            Activator.logError("Failed to write lock file", e);
        }
    }

    public void removeLockFile() {
        if (lockFilePath != null) {
            try {
                Files.deleteIfExists(lockFilePath);
                Activator.log("Lock file removed: " + lockFilePath);
            } catch (IOException e) {
                Activator.logError("Failed to remove lock file", e);
            }
            lockFilePath = null;
        }
    }

    private Path getLockFileDirectory() {
        String configDir = System.getenv("CLAUDE_CONFIG_DIR");
        if (configDir != null && !configDir.isBlank()) {
            return Path.of(configDir, "ide");
        }
        return Path.of(System.getProperty("user.home"), ".claude", "ide");
    }
}
