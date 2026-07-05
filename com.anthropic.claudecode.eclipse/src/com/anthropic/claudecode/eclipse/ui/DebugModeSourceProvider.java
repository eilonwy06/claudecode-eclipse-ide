package com.anthropic.claudecode.eclipse.ui;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.ui.AbstractSourceProvider;
import org.eclipse.ui.ISources;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;

/**
 * Publishes the current debug-mode state as a workbench source variable. Both the
 * debug activity's {@code enabledWhen} (which filters the "Claude IDE Server" view
 * and its command from Show View / key bindings) and the "Activate Claude IDE
 * Server" menu item's {@code visibleWhen} key off this single variable.
 *
 * <p>Listens to the preference store and, whenever {@link Constants#PREF_DEBUG_MODE}
 * changes, re-fires the variable. The Show View list, key bindings and the menu item
 * all update without a restart. When debug is switched off we also close the view if
 * it happens to be open.
 */
public class DebugModeSourceProvider extends AbstractSourceProvider {

    /** Must match the variable name declared in plugin.xml. */
    public static final String VARIABLE = "com.anthropic.claudecode.eclipse.debugMode";

    private final IPropertyChangeListener prefListener = new IPropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent event) {
            if (Constants.PREF_DEBUG_MODE.equals(event.getProperty())) {
                boolean debug = DebugModeUi.isDebugEnabled();
                fireSourceChanged(ISources.WORKBENCH, VARIABLE, Boolean.valueOf(debug));
                if (!debug) {
                    DebugModeUi.closeServerViewIfOpen();
                }
            }
        }
    };

    public DebugModeSourceProvider() {
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.getPreferenceStore().addPropertyChangeListener(prefListener);
        }
    }

    @Override
    public Map<String, Object> getCurrentState() {
        Map<String, Object> state = new HashMap<>(1);
        state.put(VARIABLE, Boolean.valueOf(DebugModeUi.isDebugEnabled()));
        return state;
    }

    @Override
    public String[] getProvidedSourceNames() {
        return new String[] { VARIABLE };
    }

    @Override
    public void dispose() {
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.getPreferenceStore().removePropertyChangeListener(prefListener);
        }
    }
}
