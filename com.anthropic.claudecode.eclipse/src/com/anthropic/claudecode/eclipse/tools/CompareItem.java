package com.anthropic.claudecode.eclipse.tools;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IModificationDate;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.graphics.Image;

/**
 * A compare element holding text content in memory.
 * Used by OpenDiffTool and read by AcceptDiffTool to get the current (possibly user-edited) content.
 */
class CompareItem implements ITypedElement, IStreamContentAccessor, IModificationDate, IEditableContent {

    private final String label;
    private String content;
    private final boolean editable;
    private Runnable onChange;

    CompareItem(String label, String content, boolean editable) {
        this.label = label;
        this.content = content;
        this.editable = editable;
    }

    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    @Override
    public String getName() { return label; }

    @Override
    public Image getImage() { return null; }

    @Override
    public String getType() { return ITypedElement.TEXT_TYPE; }

    @Override
    public InputStream getContents() throws CoreException {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    public String getContentString() {
        return content;
    }

    @Override
    public long getModificationDate() {
        return System.currentTimeMillis();
    }

    @Override
    public boolean isEditable() { return editable; }

    @Override
    public ITypedElement replace(ITypedElement dest, ITypedElement src) { return dest; }

    @Override
    public void setContent(byte[] newContent) {
        this.content = new String(newContent, StandardCharsets.UTF_8);
        if (onChange != null) {
            onChange.run();
        }
    }
}
