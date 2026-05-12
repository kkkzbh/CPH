package org.kkkzbh.cph;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.IconUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.List;

public final class CphToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CphToolWindowPanel panel = new CphToolWindowPanel(project);
        Content mainContent = ContentFactory.getInstance().createContent(panel, "", false);
        mainContent.setDisposer(panel);
        toolWindow.getContentManager().addContent(mainContent);
        toolWindow.setTitleActions(List.of(new ToggleSettingsAction(panel)));
    }

    private static final class ToggleSettingsAction extends AnAction implements DumbAware {
        private static final Icon SETTINGS_ICON = AllIcons.General.Settings;
        private static final Icon ACTIVE_SETTINGS_ICON = IconUtil.colorize(AllIcons.General.Settings, JBColor.BLUE);
        private final CphToolWindowPanel panel;

        private ToggleSettingsAction(@NotNull CphToolWindowPanel panel) {
            super("CPH Settings", "Toggle CPH settings", SETTINGS_ICON);
            this.panel = panel;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(panel.isSettingsViewVisible() ? ACTIVE_SETTINGS_ICON : SETTINGS_ICON);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            panel.toggleSettingsView();
            e.getPresentation().setIcon(panel.isSettingsViewVisible() ? ACTIVE_SETTINGS_ICON : SETTINGS_ICON);
        }
    }
}
