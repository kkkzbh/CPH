package org.kkkzbh.cph;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
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
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

public final class CphToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CphToolWindowPanel panel = new CphToolWindowPanel(project);
        Content mainContent = ContentFactory.getInstance().createContent(panel, "", false);
        mainContent.setDisposer(panel);
        toolWindow.getContentManager().addContent(mainContent);
        toolWindow.setTitleActions(List.of(new HelpAction(), new ToggleSettingsAction(panel)));
    }

    private static final class HelpAction extends AnAction implements DumbAware {
        private static final String DOCS_URL = "https://cph.kkkzbh.cn";
        private static final Icon HELP_ICON = new QuestionIcon(JBColor.foreground());

        private HelpAction() {
            super("CPH Help", "Open CPH help", HELP_ICON);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            BrowserUtil.browse(DOCS_URL);
        }
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

    private static final class QuestionIcon implements Icon {
        private static final int SIZE = 16;
        private final Color color;

        private QuestionIcon(@NotNull Color color) {
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                Font baseFont = c == null ? g2.getFont() : c.getFont();
                g2.setFont(baseFont.deriveFont(Font.BOLD, 15.0f));
                int textWidth = g2.getFontMetrics().stringWidth("?");
                int textX = x + (SIZE - textWidth) / 2;
                int textY = y + (SIZE - g2.getFontMetrics().getHeight()) / 2 + g2.getFontMetrics().getAscent();
                g2.drawString("?", textX, textY);
            } finally {
                g2.dispose();
            }
        }
    }
}
