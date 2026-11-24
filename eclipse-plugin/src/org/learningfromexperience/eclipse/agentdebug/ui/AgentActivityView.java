package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import org.learningfromexperience.eclipse.agentdebug.activity.ActivityPreviewHtml;
import org.learningfromexperience.eclipse.agentdebug.activity.ActivityStore;

/**
 * Agent Activity view (OPEN-36), delivery 2 preview: shows the current investigation from {@link ActivityStore} as it
 * arrives, to check that live events reach Eclipse and that the embedded browser works on this machine.
 *
 * <p>Rendering: an SWT {@link Browser} (on Windows: Edge/WebView2). When no browser can be created ({@link SWTError},
 * for example WebView2 missing), a read-only text field with the same content is used instead, and the view's
 * description says which one is active.
 *
 * <p>Updates arrive on the bridge thread; they are coalesced into at most one pending UI refresh. This view never
 * activates itself and never takes the keyboard focus on an update, so typing in the terminal is not interrupted.
 *
 * @author Ayan Dutta
 */
public final class AgentActivityView extends ViewPart {

    /** View ID, as registered in plugin.xml. */
    public static final String ID = "org.learningfromexperience.eclipse.agentdebug.activityView";

    private Browser browser;
    private Text text;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final Consumer<String> listener = id -> scheduleRefresh();

    @Override
    public void createPartControl(Composite parent) {
        try {
            browser = new Browser(parent, SWT.NONE);
            browser.setJavascriptEnabled(false);
            setContentDescription("Preview · embedded browser: " + browser.getBrowserType());
        } catch (SWTError noBrowser) {
            browser = null;
            text = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
            setContentDescription("Preview · text only (no embedded browser: " + noBrowser.getMessage() + ")");
        }
        ActivityStore.global().addListener(listener);
        refresh();
    }

    private void scheduleRefresh() {
        if (!refreshPending.compareAndSet(false, true)) return;
        Display display = control() == null ? null : control().getDisplay();
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
        ActivityStore.Investigation current = store.current();
        if (browser != null) browser.setText(ActivityPreviewHtml.page(current, store.droppedEvents()));
        else text.setText(ActivityPreviewHtml.text(current, store.droppedEvents()));
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
        super.dispose();
    }
}
