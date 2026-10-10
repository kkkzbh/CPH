package org.kkkzbh.cph;

import com.intellij.mock.Mock;
import com.intellij.openapi.vfs.VirtualFile;

public final class SelectedFileEditorManager extends Mock.MyFileEditorManager {
    private final VirtualFile file;

    public SelectedFileEditorManager(VirtualFile file) {
        this.file = file;
    }

    @Override
    public VirtualFile[] getSelectedFiles() {
        return new VirtualFile[]{file};
    }
}
