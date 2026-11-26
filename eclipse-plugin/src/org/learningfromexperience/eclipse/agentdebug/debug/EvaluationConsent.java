package org.learningfromexperience.eclipse.agentdebug.debug;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * OPEN-58: the user, not the agent, decides whether code runs in the debugged program. Evaluating a Java expression
 * ({@code EVALUATE_EXPRESSION}) or setting a tracepoint ({@code SET_TRACEPOINT}) runs code that can call methods and
 * change the program's state. The agent's {@code allowSideEffects=true} only says the agent knows this; before such
 * code runs, Eclipse asks the user ({@link Asker}, a dialog in Eclipse): Allow once, Allow for this debugging session,
 * or Deny. No answer within the time limit counts as Deny. Seen 27 Nov 2026: a model set {@code allowSideEffects=true}
 * itself after three refusals, and Copilot had every tool auto-approved, so nobody was asked.
 * <p>"Allow for this debugging session" is kept per session object (the launch for an evaluation, the investigation
 * for a tracepoint), weakly, so it ends with that session and never carries over to a later run of the same name.
 * <p>For unattended tests only, the test Eclipse ({@code AGENTDEBUG_E2E=1}) can preset the answer
 * ({@code TEST_SET_CONSENT allow|deny|ask}); a normal Eclipse always asks.
 * <p>Plain Java on purpose (no Eclipse imports): tested by EC-U1 to EC-U6 ({@code EvaluationConsentTest}).
 *
 * @author Ayan Dutta
 */
public final class EvaluationConsent {

    /** What the user answered. */
    public enum Answer { ALLOW_ONCE, ALLOW_SESSION, DENY, NO_ANSWER, CANNOT_ASK }

    /** A preset answer for unattended tests ({@link #preset(String)}); {@link #ASK} is the normal behaviour. */
    public enum Preset { ASK, ALLOW, DENY }

    /**
     * What the agent wants to run.
     *
     * @param tracepoint true for a tracepoint, false for an expression evaluation
     * @param expression the Java expression as the agent sent it
     * @param where where it runs, for the user, e.g. "OrderService.quote:23 (thread main)"
     * @param session the debugging session an "Allow for this debugging session" answer covers (the launch or the
     *                investigation object); null when there is none, then that answer covers only this request
     */
    public record Request(boolean tracepoint, String expression, String where, Object session) { }

    /** The decision; {@code message} explains a refusal to the agent. */
    public record Decision(boolean allowed, String message) {
        static Decision allow() { return new Decision(true, null); }
        static Decision refuse(String message) { return new Decision(false, message); }
    }

    /** Asks the user; returns within about {@code timeoutMillis} ({@link Answer#NO_ANSWER} after it). */
    @FunctionalInterface
    public interface Asker {
        Answer ask(Request request, long timeoutMillis);
    }

    private final Asker asker;
    private final long timeoutMillis;
    private final Set<Object> allowedSessions = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private volatile Preset preset = Preset.ASK;

    public EvaluationConsent(Asker asker, long timeoutMillis) {
        this.asker = asker;
        this.timeoutMillis = timeoutMillis;
    }

    /** The time the user has to answer, in seconds (shown in the question). */
    public long timeoutSeconds() { return timeoutMillis / 1000; }

    /**
     * Decides whether the request may run. Asks the user unless the session was allowed before or a test preset
     * applies. Never throws: a failure to ask is a refusal.
     */
    public Decision decide(Request request) {
        Preset current = preset;
        if (current == Preset.ALLOW) return Decision.allow();
        if (current == Preset.DENY) return Decision.refuse(refusal(request, "the user refused in Eclipse (test preset)"));
        if (request.session() != null && allowedSessions.contains(request.session())) return Decision.allow();
        Answer answer;
        try {
            answer = asker.ask(request, timeoutMillis);
        } catch (RuntimeException | LinkageError failure) {
            answer = Answer.CANNOT_ASK;
        }
        if (answer == null) answer = Answer.CANNOT_ASK;
        return switch (answer) {
            case ALLOW_ONCE -> Decision.allow();
            case ALLOW_SESSION -> {
                if (request.session() != null) allowedSessions.add(request.session());
                yield Decision.allow();
            }
            case DENY -> Decision.refuse(refusal(request, "the user refused in Eclipse"));
            case NO_ANSWER -> Decision.refuse(refusal(request, "no answer from the user in Eclipse within "
                    + timeoutSeconds() + " s"));
            case CANNOT_ASK -> Decision.refuse(refusal(request, "Eclipse could not ask the user (no Eclipse window)"));
        };
    }

    /** Test Eclipse only: {@code allow}, {@code deny} or {@code ask}. Returns the preset now in force. */
    public Preset preset(String value) {
        Preset chosen = switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
            case "allow" -> Preset.ALLOW;
            case "deny" -> Preset.DENY;
            case "ask" -> Preset.ASK;
            default -> throw new IllegalArgumentException("Consent preset must be allow, deny or ask");
        };
        preset = chosen;
        if (chosen == Preset.ASK) allowedSessions.clear();
        return chosen;
    }

    /** The refusal the agent gets: what did not happen, why, and what to do instead. */
    static String refusal(Request request, String why) {
        return request.tracepoint()
                ? "Refused: " + why + "; the tracepoint was not set and nothing ran. Do not retry it; use a breakpoint"
                        + " and get_frame_variables, which run no code."
                : "Refused: " + why + "; the expression was not evaluated and nothing ran. Do not retry it; use"
                        + " get_frame_variables or expand_frame_variable, which run no code.";
    }
}
