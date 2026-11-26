package org.learningfromexperience.eclipse.agentdebug.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.learningfromexperience.eclipse.agentdebug.debug.EvaluationConsent;
import org.learningfromexperience.eclipse.agentdebug.debug.EvaluationConsent.Answer;
import org.learningfromexperience.eclipse.agentdebug.debug.EvaluationConsent.Request;

/**
 * OPEN-58: the question in Eclipse before an agent runs code in the debugged program (see {@link EvaluationConsent}).
 * A standard Eclipse question dialog over the Eclipse window with three buttons: <b>Allow once</b>, <b>Allow for this
 * debugging session</b>, <b>Deny</b> (the default, so Enter never allows by accident; closing the dialog also denies).
 * When the user does not answer within the time limit, the dialog closes itself and the answer is
 * {@link Answer#NO_ANSWER}, which refuses.
 * <p>Called on a bridge thread, never on the UI thread: it opens the dialog with {@code asyncExec} and waits for the
 * answer.
 *
 * @author Ayan Dutta
 */
public final class EvaluationConsentDialog implements EvaluationConsent.Asker {

    static final String TITLE = "Agentic Debugger: allow the agent to run code?";
    private static final String[] BUTTONS = {"Allow once", "Allow for this debugging session", "Deny"};
    private static final int DENY_INDEX = 2;

    @Override
    public Answer ask(Request request, long timeoutMillis) {
        if (!PlatformUI.isWorkbenchRunning()) return Answer.CANNOT_ASK;
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) return Answer.CANNOT_ASK;
        if (display.getThread() == Thread.currentThread())
            throw new IllegalStateException("The consent question must not be asked on the UI thread");
        CompletableFuture<Answer> answer = new CompletableFuture<>();
        AtomicReference<MessageDialog> shown = new AtomicReference<>();
        display.asyncExec(() -> {
            if (answer.isDone()) return; // timed out before the UI thread got here
            try {
                MessageDialog dialog = new MessageDialog(parent(display), TITLE, null,
                        message(request, timeoutMillis / 1000), MessageDialog.QUESTION, BUTTONS, DENY_INDEX);
                shown.set(dialog);
                int index = dialog.open(); // blocks this UI call until the user answers or the dialog is closed
                answer.complete(index == 0 ? Answer.ALLOW_ONCE : index == 1 ? Answer.ALLOW_SESSION : Answer.DENY);
            } catch (RuntimeException failure) {
                answer.complete(Answer.CANNOT_ASK);
            }
        });
        try {
            return answer.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            // First complete as NO_ANSWER, then close: the closed dialog's return code is then ignored.
            answer.complete(Answer.NO_ANSWER);
            if (!display.isDisposed()) display.asyncExec(() -> {
                MessageDialog dialog = shown.get();
                if (dialog != null) dialog.close();
            });
            return answer.getNow(Answer.NO_ANSWER);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            answer.complete(Answer.NO_ANSWER);
            return Answer.NO_ANSWER;
        } catch (ExecutionException failure) {
            return Answer.CANNOT_ASK;
        }
    }

    /** The question text: what runs, where, why it matters, and what happens without an answer. */
    static String message(Request request, long timeoutSeconds) {
        String what = request.tracepoint()
                ? "The debugging agent wants to set a tracepoint that evaluates and prints this Java expression each"
                        + " time the line runs:"
                : "The debugging agent wants to evaluate this Java expression in your program:";
        return what + "\n\n    " + shorten(request.expression(), 300) + "\n\nat " + request.where() + ".\n\n"
                + "Evaluating Java can call methods and change the program's state (fields, data, files). Reading"
                + " variables does not need this. Nothing runs unless you allow it; without an answer within "
                + timeoutSeconds + " s it is denied.";
    }

    private static String shorten(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /** The Eclipse window (not the Agent Activity window) is the parent, so the question appears over Eclipse. */
    private static Shell parent(Display display) {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null && PlatformUI.getWorkbench().getWorkbenchWindowCount() > 0)
            window = PlatformUI.getWorkbench().getWorkbenchWindows()[0];
        return window != null ? window.getShell() : display.getActiveShell();
    }
}
