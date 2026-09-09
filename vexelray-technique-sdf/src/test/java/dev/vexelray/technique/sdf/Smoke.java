package dev.vexelray.technique.sdf;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape a headless smoke check has: one subject run, and at least one <b>control</b> run with a knob at an
 * absurd value that must produce a different number.
 *
 * <h2>Why a class and not a habit</h2>
 *
 * <p>A smoke that counts pixels answers "did this draw anything". It cannot answer "was this measurement capable
 * of saying no" — and an instrument that is incapable of saying no does not report a pass, it reports nothing,
 * confidently. The three worst hours of the calculator build were spent that way three separate times: a
 * seven-configuration performance sweep in which none of the flags reached the JVM and every configuration
 * therefore produced one identical number; a trace that had never compiled in, read as a handler that never
 * fired; and a driver script that toggled a panel twice.
 *
 * <p>The rule those three share was written down afterwards — <em>before trusting a measurement, move one knob
 * to an absurd value and confirm the number moves</em> — and left as discipline. Three out of three were
 * discipline failures, so it is not discipline that was missing. This makes the control a structural part of
 * the run: a smoke that records no control, or whose every control agrees with its subject, <b>fails</b>,
 * whatever the subject drew.
 *
 * <p>The control is not required to be zero. "The number moves" is the whole claim, and it is the claim that
 * distinguishes a working instrument from a constant.
 */
final class Smoke {

    private record Run(String label, int drawn, boolean control) {
    }

    private final String subject;
    private final int totalPixels;
    private final List<Run> runs = new ArrayList<>();

    Smoke(String subject, int totalPixels) {
        this.subject = subject;
        this.totalPixels = totalPixels;
    }

    /** The run being measured — what the smoke is actually about. */
    void measured(String label, int drawn) {
        add(label, drawn, false);
    }

    /** A run with one knob at a value that cannot plausibly draw the same thing. */
    void control(String label, int drawn) {
        add(label, drawn, true);
    }

    private void add(String label, int drawn, boolean control) {
        runs.add(new Run(label, drawn, control));
        System.out.printf("%-14s %-30s %8d px  (%d%%)%n",
                control ? "control" : "measured", label, drawn, Math.round(100.0 * drawn / totalPixels));
    }

    /**
     * Print the verdict and say whether the smoke passed. Three ways to fail, and the second and third are the
     * reason this class exists:
     *
     * <ul>
     *   <li>the measured run drew nothing;</li>
     *   <li>no control was recorded — nothing establishes the count can be anything other than what it was;</li>
     *   <li>every control agreed with the measured run — the knob moved and the number did not, so the number
     *       is not a function of the thing being tested.</li>
     * </ul>
     */
    boolean verdict() {
        System.out.println();
        List<Run> measured = runs.stream().filter(r -> !r.control()).toList();
        List<Run> controls = runs.stream().filter(Run::control).toList();
        if (measured.isEmpty()) {
            return fail("no measured run was recorded");
        }
        for (Run m : measured) {
            if (m.drawn() == 0) {
                return fail("NOTHING DRAWN by '" + m.label() + "'");
            }
        }
        if (controls.isEmpty()) {
            return fail("no control run was recorded -- this measurement cannot be shown to detect anything");
        }
        for (Run m : measured) {
            if (controls.stream().noneMatch(c -> c.drawn() != m.drawn())) {
                return fail("every control agrees with '" + m.label() + "' at " + m.drawn()
                        + " px -- the knob moved and the number did not, so the number is not measuring it");
            }
        }
        System.out.println(subject + ": PASS -- geometry rendered, and the count moves when it should");
        return true;
    }

    private boolean fail(String why) {
        System.out.println(subject + ": FAIL -- " + why);
        return false;
    }
}
