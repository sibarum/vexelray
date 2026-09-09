package dev.vexelray.technique.sdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Smoke} exists to stop a measurement being trusted before it has been shown capable of saying no. This
 * is that argument applied to {@code Smoke} itself: a checker whose failure has never been watched is one more
 * instrument taken on faith, and it is the only part of the arrangement that runs without a GPU.
 *
 * <p>Every case here is a way the checker must <b>fail</b>, because a checker that only ever passes is
 * indistinguishable from no checker at all — which is exactly the state the smokes were in before it.
 */
class SmokeTest {

    private static final int PIXELS = 100;

    @Test
    void aMeasuredRunThatDrewNothingFails() {
        Smoke smoke = new Smoke("nothing drawn", PIXELS);
        smoke.measured("subject", 0);
        smoke.control("knob", 7);
        assertFalse(smoke.verdict(), "a subject that drew no pixels is a failure however good the control is");
    }

    @Test
    void noControlFails() {
        Smoke smoke = new Smoke("no control", PIXELS);
        smoke.measured("subject", 42);
        assertFalse(smoke.verdict(),
                "without a control nothing establishes the count could have been anything else");
    }

    @Test
    void aControlThatAgreesWithTheSubjectFails() {
        Smoke smoke = new Smoke("constant", PIXELS);
        smoke.measured("subject", 42);
        smoke.control("knob at an absurd value", 42);
        assertFalse(smoke.verdict(), "the knob moved and the number did not, so the number measures nothing");
    }

    @Test
    void oneMovingControlAmongSeveralIsEnough() {
        Smoke smoke = new Smoke("one good control", PIXELS);
        smoke.measured("subject", 42);
        smoke.control("a knob this subject does not depend on", 42);
        smoke.control("a knob it does", 0);
        assertTrue(smoke.verdict(),
                "a control that does not move the number is uninformative, not disqualifying");
    }

    @Test
    void aSubjectAndAMovingControlPasses() {
        Smoke smoke = new Smoke("the good case", PIXELS);
        smoke.measured("subject", 42);
        smoke.control("knob", 0);
        assertTrue(smoke.verdict());
    }
}
