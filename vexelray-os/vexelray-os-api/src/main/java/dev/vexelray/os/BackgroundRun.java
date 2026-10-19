package dev.vexelray.os;

import java.nio.file.Files;
import java.nio.file.Path;

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

    // ---- The person's handle on it -------------------------------------------------------------------------------

    /**
     * One entry of the menu a background run offers the person at the desk, on the one thing of it they can see: an
     * icon where the taskbar keeps them (the notification area on Windows). {@code run} is called on the main thread.
     */
    public record MenuItem(String label, Runnable run) {
        public MenuItem {
            java.util.Objects.requireNonNull(label, "label");
            java.util.Objects.requireNonNull(run, "run");
        }
    }

    /** What the last entry says, always: the person's way to end a run they did not start. */
    public static final String EXIT_LABEL = "Exit Program";

    /**
     * The line written when the person ends a background run, and the word for it to search for. Distinctive on
     * purpose: whoever reads the log afterwards (the agent that started the run, first) must not take it for a crash.
     */
    public static final String STOPPED_BY_USER = "STOPPED BY USER";

    private static final java.util.List<MenuItem> ITEMS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Add an entry to the background run's menu, above {@link #EXIT_LABEL}, in the order added. */
    public static void addMenuItem(String label, Runnable run) {
        ITEMS.add(new MenuItem(label, run));
    }

    /** The background run's menu as it stands: the added entries, then {@link #EXIT_LABEL}. */
    public static java.util.List<MenuItem> menu(String application) {
        java.util.List<MenuItem> all = new java.util.ArrayList<>(ITEMS);
        all.add(new MenuItem(EXIT_LABEL, () -> exitByUser(application)));
        return java.util.List.copyOf(all);
    }

    /**
     * The person ended this run from its menu: say so where it cannot be mistaken for a crash, leave the agent that
     * started it a note to stop and talk to them, and exit.
     *
     * <p>The note is {@code ~/.vexelray/stopped-by-user/<session>}, named by the {@code CLAUDE_CODE_SESSION_ID} the
     * run inherited. A hook in the person's Claude Code settings refuses that session's tool calls while it exists,
     * and deletes it when the person next writes in that session. So the agent pauses until they have talked, which
     * is the point: a person who reached for the exit wants a word first.
     *
     * <p>The exit runs on a thread of its own, because this is called from inside the window procedure on the main
     * thread, and a shutdown hook that needs the main thread would otherwise wait on it for ever.
     */
    public static void exitByUser(String application) {
        Path dir = Path.of(System.getProperty("user.home"), ".vexelray", "stopped-by-user");
        stoppedByUser(dir, System.getenv("CLAUDE_CODE_SESSION_ID"), application, System.err);
        Thread exit = new Thread(() -> System.exit(0), "vexelray-exit-by-user");
        exit.start();
    }

    /** Everything {@link #exitByUser} does but exit: the line and the note. Returns the note, or null if none. */
    static Path stoppedByUser(Path dir, String session, String application, java.io.PrintStream log) {
        String app = application == null || application.isBlank() ? "the application" : application;
        String when = java.time.OffsetDateTime.now().withNano(0).toString();
        log.println("vexelray: " + STOPPED_BY_USER + ": " + app + " (pid " + ProcessHandle.current().pid()
                + ") was intentionally shut down by the user, from its taskbar icon's " + EXIT_LABEL + ", at " + when
                + ". Not a crash. Agent: stop what you are doing and talk to the user before going on.");
        log.flush();
        String id = session == null || session.isBlank() ? "no-session" : session.replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            Files.createDirectories(dir);
            Path note = dir.resolve(id);
            Files.writeString(note, STOPPED_BY_USER + "\napplication=" + app + "\npid=" + ProcessHandle.current().pid()
                    + "\nat=" + when + "\n");
            return note;
        } catch (java.io.IOException e) {
            log.println("vexelray: could not leave the stopped-by-user note in " + dir + ": " + e);
            return null;
        }
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
