package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.List;

/**
 * How Eclipse's layout follows an agent-controlled debug session (OPEN-35). Plain Java, no Eclipse classes, so it is
 * unit-tested without a workbench.
 *
 * <p><b>Default: stay in the current perspective.</b> A switch to the Debug perspective hides views that are not part
 * of it, above all the terminal in which Claude runs and may ask the user something. Instead, the debug views are
 * opened in the current perspective ({@link #DEBUG_VIEWS}) and Variables is brought to the front of its group, without
 * taking the keyboard focus. Only a user who explicitly ticked "Switch to the Debug perspective" in Preferences →
 * Agentic Debugger gets the switch; the setting was "on" by default before 1.5, but a default that was never saved is
 * not a user's choice.
 *
 * @author Ayan Dutta
 */
public final class PerspectivePolicy {

    /** Preference key of the plugin's instance scope; stored only when the user changes the checkbox. */
    public static final String SWITCH_TO_DEBUG_PERSPECTIVE = "autoDebugPerspective";

    /**
     * Views opened in the current perspective for an agent session, in this order: Debug (its selected frame decides
     * what Variables shows), Breakpoints, Expressions, Console, and Variables last because it is brought to the front.
     * IDs as in {@code IDebugUIConstants} and {@code IConsoleConstants}.
     */
    public static final List<String> DEBUG_VIEWS = List.of(
            "org.eclipse.debug.ui.DebugView",
            "org.eclipse.debug.ui.BreakpointView",
            "org.eclipse.debug.ui.ExpressionView",
            "org.eclipse.ui.console.ConsoleView",
            "org.eclipse.debug.ui.VariableView");

    /** The view brought to the front of its group when an agent session stops for the first time. */
    public static final String FRONT_VIEW = "org.eclipse.debug.ui.VariableView";

    private static final List<String> JDK_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private PerspectivePolicy() { }

    /** JDK code that the editor skips when choosing which frame to show (the Debug view still shows it). */
    public static boolean jdkType(String typeName) {
        if (typeName == null) return false;
        for (String prefix : JDK_PACKAGES) if (typeName.startsWith(prefix)) return true;
        return false;
    }

    /**
     * Whether to switch to the Debug perspective.
     *
     * @param saved the stored preference value, or null when the user never changed it
     * @return true only when the user explicitly chose the switch
     */
    public static boolean switchToDebugPerspective(String saved) {
        return Boolean.parseBoolean(saved);
    }
}
