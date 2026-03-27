package com.anthropic.claudecode.eclipse.terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.tm.terminal.view.core.TerminalServiceFactory;
import org.eclipse.tm.terminal.view.core.interfaces.ITerminalService;
import org.eclipse.tm.terminal.view.core.interfaces.constants.ITerminalsConnectorConstants;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.editor.UiHelper;

public class ClaudeProcessManager {

    private Consumer<String> statusCallback;
    private volatile boolean launched = false;

    public void setStdoutHandler(Consumer<String> handler) {}

    public void setStderrHandler(Consumer<String> handler) {}

    public void setExitHandler(Runnable handler) {}

    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    public void start(int serverPort, String authToken, String... extraArgs) throws IOException {
        if (launched) {
            report("Claude terminal is already open.\n");
            return;
        }

        String claudeCmd = Activator.getDefault().getPreferenceStore()
                .getString(Constants.PREF_CLAUDE_CMD);
        if (claudeCmd == null || claudeCmd.isBlank()) {
            claudeCmd = Constants.DEFAULT_CLAUDE_CMD;
        }

        // Build args string for extra claude args
        StringBuilder extraArgStr = new StringBuilder();
        for (String arg : extraArgs) {
            if (!extraArgStr.isEmpty()) extraArgStr.append(' ');
            extraArgStr.append(arg);
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put(ITerminalsConnectorConstants.PROP_DELEGATE_ID,
                "org.eclipse.tm.terminal.connector.local.launcher.local");
        properties.put(ITerminalsConnectorConstants.PROP_TITLE, "Claude Code");
        properties.put(ITerminalsConnectorConstants.PROP_ENCODING, "UTF-8");
        properties.put(ITerminalsConnectorConstants.PROP_FORCE_NEW, Boolean.TRUE);
        properties.put(ITerminalsConnectorConstants.PROP_DATA_NO_RECONNECT, Boolean.TRUE);

        // On Windows: use powershell.exe with -EncodedCommand to avoid quoting hell
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            properties.put(ITerminalsConnectorConstants.PROP_PROCESS_PATH, "powershell.exe");

            // Build PowerShell script that sets env vars and launches claude
            StringBuilder psScript = new StringBuilder();
            psScript.append("$env:CLAUDE_IDE_PORT='").append(serverPort).append("'; ");
            psScript.append("$env:CLAUDE_IDE_AUTH_TOKEN='").append(authToken).append("'; ");
            psScript.append("$env:CLAUDE_IDE_NAME='").append(Constants.IDE_NAME).append("'; ");
            psScript.append("$env:TERM='xterm-256color'; ");
            psScript.append("& '").append(claudeCmd).append("'");
            if (!extraArgStr.isEmpty()) {
                psScript.append(' ').append(extraArgStr);
            }

            // Encode as UTF-16LE base64 (what PowerShell -EncodedCommand expects)
            String encoded = java.util.Base64.getEncoder().encodeToString(
                    psScript.toString().getBytes(java.nio.charset.StandardCharsets.UTF_16LE));

            properties.put(ITerminalsConnectorConstants.PROP_PROCESS_ARGS,
                    "-NoExit -EncodedCommand " + encoded);

            report("Launching: " + psScript + "\n");
        } else {
            // Unix: env vars via PROP_PROCESS_ENVIRONMENT work reliably
            List<String> envList = new ArrayList<>();
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                envList.add(entry.getKey() + "=" + entry.getValue());
            }
            envList.add("CLAUDE_IDE_PORT=" + serverPort);
            envList.add("CLAUDE_IDE_AUTH_TOKEN=" + authToken);
            envList.add("CLAUDE_IDE_NAME=" + Constants.IDE_NAME);
            envList.add("TERM=xterm-256color");
            properties.put(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT, envList.toArray(new String[0]));
            properties.put(ITerminalsConnectorConstants.PROP_PROCESS_PATH, claudeCmd);
            if (!extraArgStr.isEmpty()) {
                properties.put(ITerminalsConnectorConstants.PROP_PROCESS_ARGS, extraArgStr.toString());
            }
        }

        final String cmdForLog = claudeCmd;

        UiHelper.syncExec(() -> {
            // Use workspace root so Claude can see all projects
            String workingDir = ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
            properties.put(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR, workingDir);
            report("Working directory: " + workingDir + "\n");

            // Step 1: Force the Terminal view open so user can see it
            try {
                IWorkbenchPage page = UiHelper.getActivePage();
                if (page != null) {
                    page.showView("org.eclipse.tm.terminal.view.ui.TerminalsView");
                }
            } catch (PartInitException e) {
                report("[ERROR] Could not open Terminal view: " + e.getMessage() + "\n");
                Activator.logError("Failed to open Terminal view", e);
                return;
            }

            // Step 2: Get the terminal service
            ITerminalService terminalService = TerminalServiceFactory.getService();
            if (terminalService == null) {
                report("[ERROR] TM Terminal service is not available.\n");
                report("Make sure Eclipse TM Terminal is installed (Help > Install New Software).\n");
                Activator.logError("TM Terminal service not available", null);
                return;
            }

            report("Opening terminal with command: " + cmdForLog + "\n");

            // Step 3: Open the console
            ITerminalService.Done done = status -> {
                UiHelper.asyncExec(() -> {
                    if (status.getSeverity() == IStatus.OK || status.getSeverity() == IStatus.INFO) {
                        launched = true;
                        report("Claude terminal connected.\n");
                        Activator.log("Claude terminal opened (cmd: " + cmdForLog + ")");
                    } else {
                        launched = false;
                        String msg = status.getMessage();
                        Throwable ex = status.getException();
                        report("[ERROR] Terminal failed: " + msg + "\n");
                        if (ex != null) {
                            report("[ERROR] Cause: " + ex.getMessage() + "\n");
                        }
                        report("Check: Is '" + cmdForLog + "' a valid path to claude.exe?\n");
                        Activator.logError("Terminal open failed: " + msg, ex);
                    }
                });
            };

            terminalService.openConsole(properties, done);
        });
    }

    public void stop() {
        launched = false;
    }

    public boolean isRunning() {
        return launched;
    }

    public void writeStdin(String text) throws IOException {}

    private void report(String message) {
        if (statusCallback != null) {
            statusCallback.accept(message);
        }
    }

    /**
     * Resolves the working directory from the currently selected project in Package Explorer.
     * Falls back to the workspace root if no project is selected.
     * MUST be called on the UI thread.
     */
}
