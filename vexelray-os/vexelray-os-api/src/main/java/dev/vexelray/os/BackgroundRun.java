package dev.vexelray.os;

/**
 * Whether this process is a <b>background run</b>: an application started by an agent, which drives it only through
 * its automation socket, on a desktop where a person is working.
 *
 * <p>A background run's windows are fully real (they lay out, draw, present and read back their frames exactly
 * as they would on screen) but nobody can see or reach them. They never take the foreground, never appear on top,
 * have no taskbar button, and no real click or keystroke lands on them. Every {@link NativePlatform} honours this
 * at window creation, for every window of the process, so no application wires it and none can forget it.
 *
 * <p><b>Decided from the environment, never from a flag.</b> An agent's shell carries {@code CLAUDECODE} or
 * {@code AI_AGENT}, and whatever it starts inherits them, whether through {@code mvn exec}, {@code ottermate
 * --launch} or a script. So the person at the desk is not asked to trust every agent to remember a switch: the
 * switch the agent would have had to pass is the one it cannot leave out. It was a flag first, and a flag is
 * something an agent can leave off when the visible run looks easier, so it eventually does.
 *
 * <p><b>The way out is the person's.</b> {@code VEXELRAY_FOREGROUND=1} (or {@code -Dvexelray.foreground=true})
 * opens the windows normally even under an agent: for when the person asked the agent to show them the
 * application. An agent never sets it on its own account.
 *
 * <p>{@code -Dvexelray.background=true} asks for a background run wherever it is set, and outranks the override:
 * for a test suite that opens windows, so that no build puts one on the desk. It can only hide a window, so it
 * is no way out of anything.
 *
 * <p>Read once, at first use, and fixed for the life of the process.
 */
public final class BackgroundRun {

    private static final boolean ACTIVE = decide(System.getenv(),
            System.getProperty("vexelray.foreground"), System.getProperty("vexelray.background"));

    private BackgroundRun() {
    }

    /** Whether this process's windows are to be real but unseen and unreachable. */
    public static boolean active() {
        return ACTIVE;
    }

    /** The decision itself, over a given environment and the two properties; for tests. */
    static boolean decide(java.util.Map<String, String> env, String foregroundProperty, String backgroundProperty) {
        if (yes(backgroundProperty)) {
            return true;
        }
        if (yes(env.get("VEXELRAY_FOREGROUND")) || yes(foregroundProperty)) {
            return false;
        }
        return present(env.get("CLAUDECODE")) || present(env.get("AI_AGENT"));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && !value.trim().equals("0");
    }

    private static boolean yes(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes");
    }
}
