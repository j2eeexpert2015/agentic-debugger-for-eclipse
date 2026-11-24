package org.learningfromexperience.eclipse.agentdebug.ui;

import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
import org.eclipse.ui.part.ViewPart;
import org.learningfromexperience.eclipse.agentdebug.activity.ActivityPage;
import org.learningfromexperience.eclipse.agentdebug.activity.ActivityStore;

/**
 * Agent Activity view (OPEN-36): what the agent is doing, live, from {@link ActivityStore}.
 *
 * <p><b>Rendering.</b> An SWT {@link Browser} (on Windows: Edge/WebView2) loads {@link ActivityPage#shell()} once;
 * each change is applied by one script call ({@link ActivityPage#update}), so the page does not reload: the scroll
 * position and an expanded request stay, and the timer and the blinking run inside the page. Links never navigate
 * away. When no browser can be created ({@link SWTError}, for example WebView2 missing), a read-only text field with the
 * same content is used, and the view's description says so.
 *
 * <p><b>Actions.</b> Buttons and links in the page call {@code adAction} ({@link BrowserFunction}): open a source file
 * at a line ({@link SourceOpener}), open the HTML report.
 *
 * <p><b>Never in the way.</b> Updates arrive on the bridge thread and are coalesced into at most one pending UI
 * refresh. The view never activates itself and never takes the keyboard focus on an update; it is opened by
 * {@link ActivityViewOpener} without activation, so typing in the terminal is not interrupted.
 *
 * @author Ayan Dutta
 */
public final class AgentActivityView extends ViewPart {

    /** View ID, as registered in plugin.xml. */
    public static final String ID = "org.learningfromexperience.eclipse.agentdebug.activityView";

    private Browser browser;
    private Text text;
    /** The page's script is there (checked, not only assumed from the page-loaded event). */
    private boolean loaded;
    /** The store revision the page shows; -1: nothing shown yet. */
    private long shown = -1;
    private final Runnable poll = this::poll;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final Consumer<String> listener = id -> scheduleRefresh();

    @Override
    public void createPartControl(Composite parent) {
        try {
            browser = new Browser(parent, SWT.NONE);
            browser.setJavascriptEnabled(true);   // only our own script: updates in place and the timer
            browser.addLocationListener(new LocationAdapter() {
                @Override
                public void changing(LocationEvent event) {
                    // The page is ours; nothing in it may navigate the view somewhere else.
                    String location = event.location == null ? "" : event.location;
                    if (loaded && !location.startsWith("about:") && !location.startsWith("data:")) event.doit = false;
                }
            });
            browser.addProgressListener(new ProgressAdapter() {
                @Override
                public void completed(ProgressEvent event) {
                    // Not inside the browser's own callback (a script call from there may be refused): just after it.
                    shown = -1;
                    browser.getDisplay().asyncExec(AgentActivityView.this::refresh);
                }
            });
            new BrowserFunction(browser, "adAction") {
                @Override
                public Object function(Object[] arguments) {
                    return action(arguments);
                }
            };
            browser.setText(ActivityPage.shell());
        } catch (SWTError noBrowser) {
            browser = null;
            text = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
            setContentDescription("Text only (no embedded browser: " + noBrowser.getMessage() + ")");
        }
        ActivityStore.global().addListener(listener);
        refresh();
        parent.getDisplay().timerExec(POLL_MS, poll);
    }

    /** How often the view checks that it shows the latest state (catch-up for anything missed). */
    private static final int POLL_MS = 1000;

    /** Once a second: brings the page up to date if the store changed since, then again in a second. */
    private void poll() {
        Control control = control();
        if (control == null || control.isDisposed()) return;
        if (shown != ActivityStore.global().revision()) refresh();
        control.getDisplay().timerExec(POLL_MS, poll);
    }

    private void scheduleRefresh() {
        if (!refreshPending.compareAndSet(false, true)) return;
        Control control = control();
        Display display = control == null || control.isDisposed() ? null : control.getDisplay();
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
        if (control == null || control.isDisposed()) return;
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
            browser.setText(ActivityPage.shell());
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
     * the HTML report in the system browser.
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
                        String result = SourceOpener.open(getSite().getPage(), cls, line);
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
                default -> {
                    return "Unknown action";
                }
            }
        } catch (Exception | LinkageError failure) {
            return "Failed: " + failure.getMessage();
        }
    }

    private static int parseLine(String text) {
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException none) {
            return 0;
        }
    }

    private Control control() {
        return browser != null ? browser : text;
    }

    @Override
    public void setFocus() {
        Control control = control();
        if (control != null && !control.isDisposed()) control.setFocus();
    }

    @Override
    public void dispose() {
        ActivityStore.global().removeListener(listener);
        Control control = control();
        if (control != null && !control.isDisposed()) control.getDisplay().timerExec(-1, poll);
        super.dispose();
    }
}
