package com.anthropic.claudecode.eclipse.ui;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.resource.ImageDescriptor;

abstract class DisablingAction extends Action {
    DisablingAction(String label, ImageDescriptor enabledIcon, ImageDescriptor disabledIcon) {
        super(label);
        if (enabledIcon != null) setImageDescriptor(enabledIcon);
        if (disabledIcon != null) setDisabledImageDescriptor(disabledIcon);
    }

    abstract void updateEnabled();
}
