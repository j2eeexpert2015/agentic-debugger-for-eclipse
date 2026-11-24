package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.List;

/**
 * How Eclipse's layout follows an agent-controlled debug session (OPEN-35). Plain Java, no Eclipse classes, so it is
 * unit-tested without a workbench.
 *
 * <p><b>Default: switch to the Debug perspective (OPEN-36), first.</b> When an agent investigation starts, Eclipse
 * switches to the Debug perspective before the Agent Activity view opens, so the view opens where the session runs and
 * a switch at the first stop cannot leave it behind in the old perspective. The switch changes only the page, it does
 * not bring the Eclipse window to the front, so the keyboard stays in the terminal. A user who turns "Switch to the
 * Debug perspective" off in Preferences &gt; Agentic Debugger stays in the current perspective: the debug views are
 * opened there ({@link #DEBUG_VIEWS}) and Variables is brought to the front of its group, without taking the focus.
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

    /**
     * Preference key: show Agent Activity (OPEN-36) in its own window (true, default) or as a docked view (false);
     * stored only when the user changes it. The key name is kept from the earlier floating view, so a user who turned
     * floating off keeps the docked view.
     */
    public static final String FLOATING_ACTIVITY_VIEW = "activityViewFloating";

    /** Preference key: where the user last put the Agent Activity window, as "x,y,width,height". */
    public static final String ACTIVITY_WINDOW_BOUNDS = "activityWindowBounds";

    /** Smallest size of the Agent Activity window when it is first opened. */
    public static final int FLOATING_MIN_WIDTH = 460, FLOATING_MIN_HEIGHT = 420;

    /** Smallest size accepted from a saved place. */
    private static final int SAVED_MIN = 200;

    private PerspectivePolicy() { }

    /**
     * Whether Agent Activity opens in its own window: yes unless the user turned it off. The window belongs to the
     * Eclipse window, not to a perspective, so no perspective change, view or console output can hide it, and it can be
     * dragged anywhere (even to a second screen).
     */
    public static boolean floatingActivityView(String saved) {
        return saved == null || Boolean.parseBoolean(saved);
    }

    /** A saved window place "x,y,width,height" as {x, y, width, height}; null when missing, malformed or too small. */
    public static int[] parseBounds(String saved) {
        if (saved == null) return null;
        String[] parts = saved.trim().split(",");
        if (parts.length != 4) return null;
        int[] b = new int[4];
        try {
            for (int i = 0; i < 4; i++) b[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
        return b[2] < SAVED_MIN || b[3] < SAVED_MIN ? null : b;
    }

    /** The form {@link #parseBounds} reads. */
    public static String formatBounds(int x, int y, int width, int height) {
        return x + "," + y + "," + width + "," + height;
    }

    /**
     * Whether the window's title bar (its top 30 pixels) is on one of the screens enough to grab it: at least 100
     * pixels wide (or the whole width of a narrower window) and 20 high. A saved place from a screen that is no longer
     * connected fails this, and the window opens at its first place instead.
     *
     * @param bounds {x, y, width, height} of the window
     * @param screens each screen's usable area as {x, y, width, height}
     */
    public static boolean titleOnScreen(int[] bounds, List<int[]> screens) {
        if (bounds == null || screens == null) return false;
        int top = bounds[1], bottom = bounds[1] + 30;
        for (int[] s : screens) {
            int wide = Math.min(s[0] + s[2], bounds[0] + bounds[2]) - Math.max(s[0], bounds[0]);
            int high = Math.min(s[1] + s[3], bottom) - Math.max(s[1], top);
            if (wide >= Math.min(100, bounds[2]) && high >= 20) return true;
        }
        return false;
    }

    /**
     * Where and how big the Agent Activity window first opens: at the right edge of the Eclipse window, reaching from
     * near the bottom up to a little above the middle (58% of the window's height), about 35% of its width (460 to 640
     * pixels; about 480 on a 1366 pixel screen), always inside the window. Afterwards it opens where the user last put it.
     * Returns {x, y, width, height}.
     */
    public static int[] floatingBounds(int windowX, int windowY, int windowWidth, int windowHeight) {
        int width = Math.min(Math.max(FLOATING_MIN_WIDTH, windowWidth * 35 / 100), 640);
        width = Math.max(200, Math.min(width, windowWidth - 20));
        int height = Math.max(FLOATING_MIN_HEIGHT, windowHeight * 58 / 100);
        height = Math.max(200, Math.min(height, windowHeight - 60));
        int x = windowX + Math.max(0, windowWidth - width - 8);
        int y = windowY + Math.max(0, windowHeight - height - 40);
        return new int[] {x, y, width, height};
    }

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
     * @return true unless the user turned the switch off (OPEN-36: the switch happens first, then Agent Activity opens
     *         in the Debug perspective, so a later switch cannot leave the view behind)
     */
    public static boolean switchToDebugPerspective(String saved) {
        return saved == null || Boolean.parseBoolean(saved);
    }
}
