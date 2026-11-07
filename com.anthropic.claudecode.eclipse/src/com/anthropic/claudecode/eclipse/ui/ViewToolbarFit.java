package com.anthropic.claudecode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IPerspectiveListener;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PerspectiveAdapter;

/**
 * Keeps a view's toolbar on the tab row of its part stack when a perspective is switched to.
 *
 * <p>The stack's {@link CTabFolder} measures its tab row when it lays out, and while a
 * perspective is being switched to the toolbar parked at its right edge has no width yet,
 * so the row is measured for the tab alone. The toolbar then gets its real size, a few
 * pixels taller than that row, and the folder moves it to a second row over the view. Nothing
 * measures the row again, so the toolbar stays there until a tab is clicked or the view is
 * resized. {@code setTabHeight(SWT.DEFAULT)} keeps the height automatic and measures it
 * again, now against the toolbar's real size.
 */
final class ViewToolbarFit {

    private ViewToolbarFit() {
    }

    /** Measures the tab row of {@code content}'s part stack again each time a perspective
     *  is switched to, and once now for a view created by the switch. */
    static void install(IViewPart view, Control content) {
        IWorkbenchWindow window = view.getSite().getWorkbenchWindow();
        Display display = content.getDisplay();
        // Queued behind the stack's own re-packing of its toolbar, which is itself queued
        // while the perspective is being switched to; the second hop is what puts this after it.
        Runnable fit = () -> display.asyncExec(() -> display.asyncExec(() -> {
            if (content.isDisposed()) return;
            for (Composite c = content.getParent(); c != null; c = c.getParent()) {
                if (c instanceof CTabFolder folder) {
                    folder.setTabHeight(SWT.DEFAULT);
                    return;
                }
            }
        }));
        IPerspectiveListener listener = new PerspectiveAdapter() {
            @Override
            public void perspectiveActivated(IWorkbenchPage page, IPerspectiveDescriptor perspective) {
                fit.run();
            }
        };
        window.addPerspectiveListener(listener);
        content.addDisposeListener(e -> window.removePerspectiveListener(listener));
        fit.run();
    }
}
