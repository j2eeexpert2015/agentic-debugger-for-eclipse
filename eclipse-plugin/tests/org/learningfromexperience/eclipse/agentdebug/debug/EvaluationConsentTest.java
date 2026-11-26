package org.learningfromexperience.eclipse.agentdebug.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.learningfromexperience.eclipse.agentdebug.debug.EvaluationConsent.Answer;
import org.learningfromexperience.eclipse.agentdebug.debug.EvaluationConsent.Decision;
import org.learningfromexperience.eclipse.agentdebug.debug.EvaluationConsent.Request;

/**
 * JUnit 5 unit tests for the user's consent before code runs in the debugged program, OPEN-58 (EC-U1 to EC-U6).
 * No Eclipse needed: run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class EvaluationConsentTest {

    /** An asker that answers from a queue and counts the questions. */
    private static final class ScriptedAsker implements EvaluationConsent.Asker {
        final Deque<Answer> answers = new ArrayDeque<>();
        final AtomicInteger asked = new AtomicInteger();
        long timeoutSeen;
        @Override public Answer ask(Request request, long timeoutMillis) {
            asked.incrementAndGet();
            timeoutSeen = timeoutMillis;
            return answers.isEmpty() ? Answer.NO_ANSWER : answers.removeFirst();
        }
    }

    private static Request evaluate(Object session) { return new Request(false, "order.total()", "OrderService.quote:23", session); }

    @Test @DisplayName("EC-U1 Allow once runs this request only; the next one is asked again")
    void ecU1() {
        ScriptedAsker asker = new ScriptedAsker();
        asker.answers.add(Answer.ALLOW_ONCE);
        asker.answers.add(Answer.DENY);
        EvaluationConsent consent = new EvaluationConsent(asker, 90_000);
        Object session = new Object();
        assertTrue(consent.decide(evaluate(session)).allowed());
        assertFalse(consent.decide(evaluate(session)).allowed());
        assertEquals(2, asker.asked.get());
        assertEquals(90_000, asker.timeoutSeen);
    }

    @Test @DisplayName("EC-U2 Allow for this debugging session is not asked again for that session, but for another one")
    void ecU2() {
        ScriptedAsker asker = new ScriptedAsker();
        asker.answers.add(Answer.ALLOW_SESSION);
        asker.answers.add(Answer.DENY);
        EvaluationConsent consent = new EvaluationConsent(asker, 90_000);
        Object first = new Object(), second = new Object();
        assertTrue(consent.decide(evaluate(first)).allowed());
        assertTrue(consent.decide(evaluate(first)).allowed(), "same session: not asked again");
        assertEquals(1, asker.asked.get());
        assertFalse(consent.decide(evaluate(second)).allowed(), "another session is asked");
        assertEquals(2, asker.asked.get());
    }

    @Test @DisplayName("EC-U3 Deny, no answer and a failure to ask all refuse, and say what to do instead")
    void ecU3() {
        ScriptedAsker asker = new ScriptedAsker();
        asker.answers.add(Answer.DENY);
        asker.answers.add(Answer.NO_ANSWER);
        EvaluationConsent consent = new EvaluationConsent(asker, 90_000);
        Decision denied = consent.decide(evaluate(null));
        assertFalse(denied.allowed());
        assertTrue(denied.message().contains("the user refused in Eclipse"), denied.message());
        assertTrue(denied.message().contains("get_frame_variables"), denied.message());
        Decision silent = consent.decide(evaluate(null));
        assertFalse(silent.allowed());
        assertTrue(silent.message().contains("within 90 s"), silent.message());
        EvaluationConsent broken = new EvaluationConsent((request, timeout) -> { throw new IllegalStateException("no display"); }, 1_000);
        Decision failed = broken.decide(evaluate(null));
        assertFalse(failed.allowed());
        assertTrue(failed.message().contains("could not ask"), failed.message());
    }

    @Test @DisplayName("EC-U4 a tracepoint refusal says the tracepoint was not set")
    void ecU4() {
        EvaluationConsent consent = new EvaluationConsent((request, timeout) -> Answer.DENY, 90_000);
        Decision decision = consent.decide(new Request(true, "\"x=\" + x", "OrderService line 23", null));
        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("tracepoint was not set"), decision.message());
    }

    @Test @DisplayName("EC-U5 test presets: allow and deny answer without asking; ask asks again and forgets sessions")
    void ecU5() {
        ScriptedAsker asker = new ScriptedAsker();
        EvaluationConsent consent = new EvaluationConsent(asker, 90_000);
        Object session = new Object();
        assertEquals(EvaluationConsent.Preset.ALLOW, consent.preset("allow"));
        assertTrue(consent.decide(evaluate(session)).allowed());
        consent.preset(" DENY ");
        Decision denied = consent.decide(evaluate(session));
        assertFalse(denied.allowed());
        assertTrue(denied.message().contains("test preset"), denied.message());
        assertEquals(0, asker.asked.get(), "presets never ask");
        asker.answers.add(Answer.ALLOW_SESSION);
        consent.preset("ask");
        assertTrue(consent.decide(evaluate(session)).allowed());
        consent.preset("ask");
        asker.answers.add(Answer.DENY);
        assertFalse(consent.decide(evaluate(session)).allowed(), "ask clears earlier session answers");
        assertEquals(2, asker.asked.get());
        assertThrows(IllegalArgumentException.class, () -> consent.preset("always"));
    }

    @Test @DisplayName("EC-U6 the default is to ask")
    void ecU6() {
        ScriptedAsker asker = new ScriptedAsker();
        asker.answers.add(Answer.ALLOW_ONCE);
        assertTrue(new EvaluationConsent(asker, 5_000).decide(evaluate(null)).allowed());
        assertEquals(1, asker.asked.get());
    }
}
