package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.learningfromexperience.eclipse.agentdebug.integration.PreferenceStates;

/**
 * OPEN-69: the building blocks of the three preference pages: a line saying who a page is for, a titled group with an
 * ⓘ tooltip, and a {@link Section} (status line, optional note, buttons) that shows exactly what
 * {@link PreferenceStates} says, including which buttons are enabled.
 *
 * @author Ayan Dutta
 */
final class PrefUi {

    static final int WIDTH = 480;

    private PrefUi() { }

    /** "For Claude Code only." in bold, then one plain line. */
    static void audience(Composite root, String who, String what) {
        Label label = new Label(root, SWT.WRAP);
        label.setText(who);
        label.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        if (what != null) wrap(root, what);
    }

    /** A titled group; {@code tip}, when given, is the group's tooltip (the ⓘ of the mockup). */
    static Composite group(Composite root, String title, String tip) {
        Group group = new Group(root, SWT.NONE);
        group.setText(tip == null ? title : title + "  ⓘ");
        if (tip != null) group.setToolTipText(tip);
        group.setLayout(new GridLayout(1, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return group;
    }

    static Label wrap(Composite root, String text) {
        Label label = new Label(root, SWT.WRAP);
        label.setText(text == null ? "" : text);
        GridData layout = new GridData(SWT.FILL, SWT.TOP, true, false);
        layout.widthHint = WIDTH;
        label.setLayoutData(layout);
        return label;
    }

    /** A check box with its explanation as a tooltip. */
    static Button check(Composite root, String text, String tip, boolean selected) {
        Button box = new Button(root, SWT.CHECK);
        box.setText(tip == null ? text : text + "  ⓘ");
        if (tip != null) box.setToolTipText(tip);
        box.setSelection(selected);
        return box;
    }

    static Color colour(PreferenceStates.Tone tone) {
        return switch (tone) {
            case OK -> new Color(21, 128, 61);
            case WARN -> new Color(180, 83, 9);
            case BAD -> new Color(185, 28, 28);
            case MUTED -> new Color(75, 85, 99);
        };
    }

    /**
     * A status line, an optional note and buttons, drawn from a {@link PreferenceStates.View}: a button is shown when
     * the view enables it; while the section is busy (nothing enabled) the buttons shown before stay, disabled, so the
     * layout does not jump. The view's main button is bold.
     */
    static final class Section {
        private final Composite root;
        private final Label status;
        private final Label note;
        private final Map<PreferenceStates.Action, Button> buttons = new EnumMap<>(PreferenceStates.Action.class);
        /** Whether a settled (not busy) state was shown yet: before it, a busy section shows no buttons. */
        private boolean settled;

        Section(Composite group, Map<PreferenceStates.Action, String> labels, Consumer<PreferenceStates.Action> onClick) {
            this.root = group;
            Composite row = new Composite(group, SWT.NONE);
            GridLayout layout = new GridLayout(1 + labels.size(), false);
            layout.marginWidth = 0;
            layout.marginHeight = 0;
            row.setLayout(layout);
            row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            status = new Label(row, SWT.NONE);
            status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            status.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
            for (Map.Entry<PreferenceStates.Action, String> e : new LinkedHashMap<>(labels).entrySet()) {
                Button b = new Button(row, SWT.PUSH);
                b.setText(e.getValue());
                b.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
                PreferenceStates.Action action = e.getKey();
                b.addListener(SWT.Selection, event -> onClick.accept(action));
                buttons.put(action, b);
            }
            note = wrap(group, "");
        }

        void show(PreferenceStates.View view) {
            if (status.isDisposed()) return;
            status.setText(view.status());
            status.setForeground(colour(view.tone()));
            boolean busy = view.enabled().isEmpty() && view.tone() == PreferenceStates.Tone.MUTED
                    && view.status().endsWith("…");
            for (Map.Entry<PreferenceStates.Action, Button> e : buttons.entrySet()) {
                Button b = e.getValue();
                boolean visible = busy ? settled && b.getVisible() : view.can(e.getKey());
                b.setVisible(visible);
                ((GridData) b.getLayoutData()).exclude = !visible;
                b.setEnabled(view.can(e.getKey()));
                b.setFont(e.getKey() == view.main() ? JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT)
                        : JFaceResources.getFontRegistry().get(JFaceResources.DIALOG_FONT));
            }
            if (!busy) settled = true;
            note.setText(view.note() == null ? "" : view.note());
            note.setVisible(view.note() != null);
            ((GridData) note.getLayoutData()).exclude = view.note() == null;
            root.getShell().layout(true, true);
        }
    }
}
