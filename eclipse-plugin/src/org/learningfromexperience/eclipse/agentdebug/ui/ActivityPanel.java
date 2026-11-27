package org.learningfromexperience.eclipse.agentdebug.ui;

import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPage;
import org.learningfromexperience.agentdebug.report.activity.ActivityPage;
import org.learningfromexperience.agentdebug.report.activity.ActivityStore;

/**
 * The content of Agent Activity (OPEN-36): what the agent is doing, live, from {@link ActivityStore}. Used by both
 * places that show it: the Agent Activity window ({@link ActivityWindow}, default) and the Agent Activity view
 * ({@link AgentActivityView}, for users who want it docked).
 *
 * <p><b>Rendering.</b> An SWT {@link Browser} (on Windows: Edge/WebView2) loads {@link ActivityPage#shell()} once;
 * each change is applied by one script call ({@link ActivityPage#update}), so the page does not reload: the scroll
 * position and an expanded request stay, and the timer and the blinking run inside the page. Links never navigate
 * away. When no browser can be created ({@link SWTError}, for example WebView2 missing), a read-only text field with the
 * same content is used, and the owner is told (view description or window title).
 *
 * <p><b>Actions.</b> Buttons and links in the page call {@code adAction} ({@link BrowserFunction}): open a source file
 * at a line ({@link SourceOpener}), open the HTML report.
 *
 * <p><b>Never in the way.</b> Updates arrive on the bridge thread and are coalesced into at most one pending UI
 * refresh; the panel never takes the keyboard focus on an update. Once a second it checks that it shows the latest
 * state (catch-up for anything missed).
 *
 * @author Ayan Dutta
 */
final class ActivityPanel {

    /** How often the panel checks that it shows the latest state. */
    private static final int POLL_MS = 1000;

    private final Supplier<IWorkbenchPage> page;
    private Browser browser;
    private Text text;
    /** The page's script is there (checked, not only assumed from the page-loaded event). */
    private boolean loaded;
    /** The store revision the page shows; -1: nothing shown yet. */
    private long shown = -1;
    private boolean disposed;
    private final Runnable poll = this::poll;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final Consumer<String> listener = id -> scheduleRefresh();

    /**
     * @param parent where the browser (or the text fallback) is created
     * @param page the workbench page in which source links open an editor
     * @param textOnly told the reason when no browser can be created and the text fallback is used
     */
    ActivityPanel(Composite parent, Supplier<IWorkbenchPage> page, Consumer<String> textOnly) {
        this.page = page;
        try {
            browser = new Browser(parent, SWT.NONE);
            browser.setJavascriptEnabled(true);   // only our own script: updates in place and the timer
            browser.addLocationListener(new LocationAdapter() {
                @Override
                public void changing(LocationEvent event) {
                    // The page is ours; nothing in it may navigate somewhere else.
                    String location = event.location == null ? "" : event.location;
                    if (loaded && !location.startsWith("about:") && !location.startsWith("data:")) event.doit = false;
                }
            });
            browser.addProgressListener(new ProgressAdapter() {
                @Override
                public void completed(ProgressEvent event) {
                    // Not inside the browser's own callback (a script call from there may be refused): just after it.
                    shown = -1;
                    browser.getDisplay().asyncExec(ActivityPanel.this::refresh);
                }
            });
            new BrowserFunction(browser, "adAction") {
                @Override
                public Object function(Object[] arguments) {
                    return action(arguments);
                }
            };
            browser.setText(ActivityPage.shell(dark()));
        } catch (SWTError noBrowser) {
            browser = null;
            text = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
            textOnly.accept("Text only (no embedded browser: " + noBrowser.getMessage() + ")");
        }
        ActivityStore.global().addListener(listener);
        refresh();
        parent.getDisplay().timerExec(POLL_MS, poll);
    }

    /** Once a second: brings the page up to date if the store changed since, then again in a second. */
    private void poll() {
        Control control = control();
        if (disposed || control == null || control.isDisposed()) return;
        if (shown != ActivityStore.global().revision()) refresh();
        control.getDisplay().timerExec(POLL_MS, poll);
    }

    private void scheduleRefresh() {
        if (!refreshPending.compareAndSet(false, true)) return;
        Control control = control();
        Display display = disposed || control == null || control.isDisposed() ? null : control.getDisplay();
        if (display == null || display.isDisposed()) {
            refreshPending.set(false);
            return;
        }
        display.asyncExec(() -> {
            refreshPending.set(false);
            refresh();
        });
    }

    private void refresh() {
        Control control = control();
        if (disposed || control == null || control.isDisposed()) return;
        ActivityStore store = ActivityStore.global();
        long revision = store.revision();
        ActivityStore.Investigation current = store.current();
        ZoneId zone = ZoneId.systemDefault();
        if (browser == null) {
            text.setText(ActivityPage.text(current, store.droppedEvents(), zone));
            shown = revision;
            return;
        }
        if (!pageReady()) return;                     // the next poll tries again
        if (browser.execute(ActivityPage.update(current, store.droppedEvents(), zone))) {
            shown = revision;
        } else {
            // The script could not run: the page is not (or no longer) there. Load it again; a poll fills it.
            loaded = false;
            browser.setText(ActivityPage.shell(dark()));
        }
    }

    /**
     * True when the page's script is there. Asked from the page itself instead of relying only on the page-loaded
     * event, which some browser versions do not send for a page set with setText.
     */
    private boolean pageReady() {
        if (loaded) return true;
        try {
            loaded = "function".equals(browser.evaluate("return typeof adUpdate;"));
        } catch (RuntimeException notYet) {
            loaded = false;
        }
        return loaded;
    }

    /**
     * The page's buttons and links: {@code open} (class, line) opens the source at that line, {@code openReport} opens
     * the HTML report in the system browser, {@code theme} ("dark" or "light") keeps the look the user chose.
     * Returns a short message the page shows for a moment. Runs on the UI thread; never throws.
     */
    private String action(Object[] arguments) {
        try {
            String name = arguments.length > 0 ? String.valueOf(arguments[0]) : "";
            ActivityStore.Investigation current = ActivityStore.global().current();
            switch (name) {
                case "open" -> {
                    String cls = arguments.length > 1 ? String.valueOf(arguments[1]) : "";
                    int line = arguments.length > 2 ? parseLine(String.valueOf(arguments[2])) : 0;
                    // Opened just after the click's callback returns, so the editor can take the focus normally.
                    browser.getDisplay().asyncExec(() -> {
                        IWorkbenchPage target = page.get();
                        String result = target == null ? "No Eclipse window to open the file in"
                                : SourceOpener.open(target, cls, line);
                        if (!result.startsWith("Opened") && browser != null && !browser.isDisposed())
                            browser.execute("adToast(" + ActivityPage.js(result) + ");");
                    });
                    return "Opening " + cls + (line > 0 ? ":" + line : "");
                }
                case "openReport" -> {
                    if (current == null || current.reportFile() == null) return "No report for this investigation";
                    if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(current.reportFile())))
                        return "The report file is not there any more";
                    return Program.launch(current.reportFile()) ? "Opening the report" : "Could not open the report";
                }
                case "stop" -> {
                    // OPEN-62: asked after the click's callback returns (a dialog inside the callback can hang Edge).
                    browser.getDisplay().asyncExec(ActivityPanel::confirmAndStop);
                    return "";
                }
                case "theme" -> {
                    // The page has already switched; kept for the next load, in this window and the docked view.
                    var preferences = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
                    preferences.put(PerspectivePolicy.ACTIVITY_THEME,
                            "dark".equals(arguments.length > 1 ? String.valueOf(arguments[1]) : "") ? "dark" : "light");
                    preferences.flush();
                    return "";
                }
                default -> {
                    return "Unknown action";
                }
            }
        } catch (Exception | LinkageError failure) {
            return "Failed: " + failure.getMessage();
        }
    }

    private static final String PLUGIN_ID = "org.learningfromexperience.eclipse.agentdebug";

    /** OPEN-62: "Stop and clean up": asks once, then cleans up in the background and marks the run stopped. */
    private static void confirmAndStop() {
        var display = org.eclipse.swt.widgets.Display.getCurrent();
        var shell = display == null ? null : display.getActiveShell();
        boolean yes = org.eclipse.jface.dialogs.MessageDialog.openQuestion(shell, "Agentic Debugger",
                "Stop the agent's debugging now?\n\n"
                + "Ends the agent's own program.\nRemoves the agent's own breakpoints.\n"
                + "Your own debug sessions are not touched.\n\n"
                + "Also click the stop button in Copilot Chat if the agent is still working.");
        if (!yes) return;
        org.eclipse.core.runtime.jobs.Job job = org.eclipse.core.runtime.jobs.Job.create(
                "Agentic Debugger: stop and clean up", monitor -> {
            java.util.List<String> said = org.learningfromexperience.eclipse.agentdebug.debug.EclipseAgentOperations.stopByUser();
            ActivityStore.global().stoppedByUser(said);
            System.out.println("[Eclipse Agentic Debugger] Stop and clean up: " + String.join(" | ", said));
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.setUser(false);
        job.schedule();
    }

    /** True when the user chose the dark look (the Dark / Light button of the page); light by default. */
    private static boolean dark() {
        try {
            return "dark".equals(InstanceScope.INSTANCE.getNode(PLUGIN_ID).get(PerspectivePolicy.ACTIVITY_THEME, "light"));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static int parseLine(String text) {
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException none) {
            return 0;
        }
    }

    /** The browser, or the text field when no browser could be created. */
    Control control() {
        return browser != null ? browser : text;
    }

    void setFocus() {
        Control control = control();
        if (control != null && !control.isDisposed()) control.setFocus();
    }

    /** Stops listening and polling; the widgets are disposed with their parent. */
    void dispose() {
        if (disposed) return;
        disposed = true;
        ActivityStore.global().removeListener(listener);
        Control control = control();
        if (control != null && !control.isDisposed()) control.getDisplay().timerExec(-1, poll);
    }
}
