package com.anthropic.claudecode.eclipse.ui.actions;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;

public class OpenCliHereAction implements IObjectActionDelegate {

    private IStructuredSelection selection;

    @Override
    public void setActivePart(IAction action, IWorkbenchPart targetPart) {
    }

    @Override
    public void run(IAction action) {
        if (selection == null || selection.isEmpty()) return;

        Object element = selection.getFirstElement();
        if (element == null) return;

        IResource resource = null;
        if (element instanceof IResource) {
            resource = (IResource) element;
        } else if (element instanceof IAdaptable) {
            resource = ((IAdaptable) element).getAdapter(IResource.class);
        }

        if (resource == null) return;

        String cwd;
        String label;

        if (resource instanceof IFile) {
            IContainer parent = resource.getParent();
            if (parent == null || parent.getLocation() == null) return;
            cwd = parent.getLocation().toOSString();
            label = buildLabel(parent);
        } else if (resource instanceof IContainer) {
            if (resource.getLocation() == null) return;
            cwd = resource.getLocation().toOSString();
            label = buildLabel(resource);
        } else {
            return;
        }

        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return;

            IWorkbenchPage page = window.getActivePage();
            if (page == null) return;

            if (!Activator.getDefault().isServerRunning()) {
                Activator.getDefault().initialize();
            }

            ClaudeCliView view = (ClaudeCliView) page.showView(ClaudeCliView.VIEW_ID);
            view.launchProcessInDirectory(cwd, label);
        } catch (Exception e) {
            Activator.logError("Failed to open Claude CLI", e);
        }
    }

    @Override
    public void selectionChanged(IAction action, ISelection sel) {
        if (sel instanceof IStructuredSelection) {
            this.selection = (IStructuredSelection) sel;
        } else {
            this.selection = null;
        }
    }

    private String buildLabel(IResource resource) {
        String projectName = resource.getProject().getName();
        String resourcePath = resource.getProjectRelativePath().toString();

        if (resourcePath.isEmpty()) {
            // Resource is the project itself
            return projectName;
        } else {
            return projectName + "/" + resourcePath;
        }
    }
}
