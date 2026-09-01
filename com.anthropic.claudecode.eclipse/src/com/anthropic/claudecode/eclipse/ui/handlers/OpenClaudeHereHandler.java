package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.claudecode.eclipse.Activator;

/**
 * Shared half of the navigator's "Open Claude Here" submenu: resolve the selected
 * folder, make sure the server is up, and hand the folder to a view.
 *
 * <p>Both entries are commands rather than the older {@code objectContribution}
 * actions because only {@code org.eclipse.ui.menus} lets the SUBMENU carry an icon —
 * a {@code <menu>} under {@code org.eclipse.ui.popupMenus} has no icon attribute at
 * all (no {@code <menu>} in any of Eclipse's own popupMenus contributions declares
 * one). The behaviour is otherwise identical to what the actions did.
 */
abstract class OpenClaudeHereHandler extends AbstractHandler {

    /** Opens {@code folder} in whichever view this entry targets, on a running server. */
    protected abstract void open(IWorkbenchPage page, IResource folder) throws Exception;

    /** What to say in the log when opening fails. */
    protected abstract String failureMessage();

    @Override
    public Object execute(ExecutionEvent event) {
        try {
            IResource folder = resolveContainer(HandlerUtil.getCurrentSelection(event));
            if (folder == null) return null;

            IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
            if (window == null) return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null) return null;

            if (!Activator.getDefault().isServerRunning()) {
                Activator.getDefault().initialize();
            }
            open(page, folder);
        } catch (Exception e) {
            Activator.logError(failureMessage(), e);
        }
        return null;
    }

    /**
     * The folder a navigator selection means: the resource itself when it is a
     * container, its parent when it is a file. {@code null} when the selection is
     * empty, not adaptable to a resource, or has no filesystem location.
     *
     * <p>Adapts rather than casts, so it also resolves the Java-model nodes the
     * Package Explorer actually hands over ({@code IJavaProject},
     * {@code IPackageFragment}) — the equivalent of the old contribution's
     * {@code adaptable="true"}.
     */
    static IResource resolveContainer(ISelection selection) {
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) return null;

        Object element = structured.getFirstElement();
        IResource resource = null;
        if (element instanceof IResource r) {
            resource = r;
        } else if (element instanceof IAdaptable adaptable) {
            resource = adaptable.getAdapter(IResource.class);
        }
        if (resource == null) return null;

        IResource target = (resource instanceof IFile) ? resource.getParent()
                         : (resource instanceof IContainer) ? resource : null;
        return (target == null || target.getLocation() == null) ? null : target;
    }

    /** The label a terminal tab gets: {@code project} or {@code project/path/inside}. */
    static String labelFor(IResource resource) {
        String projectName = resource.getProject().getName();
        String path = resource.getProjectRelativePath().toString();
        return path.isEmpty() ? projectName : projectName + "/" + path;
    }
}
