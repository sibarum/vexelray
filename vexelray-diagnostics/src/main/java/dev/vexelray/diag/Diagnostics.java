package dev.vexelray.diag;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The channel by which a seam says it has silently dropped something a consumer asked for.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The failures that cost a real application built on this framework the most were not the ones that threw.
 * A technique handed a surface carrying colour it cannot render; a headless capture handed an image belonging
 * to another device; a charset asking for arrows from a font that has none. Each is one boolean away from being
 * reported, and none of them was: nothing threw, nothing warned, and <b>each produced a plausible picture</b> —
 * a plot in one colour, a screenshot correct about the chrome, a label reading {@code x □ (Re, Im)}.
 *
 * <p>That is the failure mode this addresses, and its distinguishing feature is that the output is wrong in a
 * way that reads as a taste decision. A consumer does not go looking for a bug in something that looks
 * deliberate. The framework already has the instinct one level down — a headless smoke exists precisely because
 * <i>"nothing renders" is three questions a window cannot tell apart</i> — and <b>a capability that is silently
 * dropped is a fourth question</b>.
 *
 * <h2>Why one class rather than five call sites</h2>
 *
 * <p>Each of those five fixes is a {@code System.err.println}. Five of them across three modules is five
 * formats, five decisions about repetition, and five things that cannot be asserted on. This is the same line
 * written once: warn once per key, on by default, and recorded so a test can prove the warning fires — because
 * a warning nobody has watched fire is one more instrument taken on faith.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><b>Warn once per key.</b> These sit at composition seams that run per pipeline, per frame or per glyph;
 *       a warning that repeats is a warning that gets filtered out.</li>
 *   <li><b>The key identifies the call site, not the data.</b> {@code "ConeField.compose/albedo"}, not the name
 *       of the surface. A data-derived key defeats warn-once and grows this class's memory with it — see
 *       {@link #KEY_LIMIT}.</li>
 *   <li><b>On by default</b>, silenced with {@code -Dvexelray.diag=off}. A diagnostic a consumer has to opt into
 *       is one the consumer who needed it never saw.</li>
 *   <li><b>Recording is independent of printing</b>, so {@code -Dvexelray.diag=off} does not disable the tests
 *       that assert these fire.</li>
 * </ul>
 */
public final class Diagnostics {

    /**
     * How many distinct keys are remembered before this class stops taking new ones.
     *
     * <p>Warn-once means remembering every key forever, and "forever" plus a caller who built a key out of its
     * data is a leak in a long-running application — which is a worse fault than the one being reported. The
     * cap is far above the number of composition seams that could ever exist and far below anything that
     * matters as memory; hitting it means a key is data-derived, so hitting it says so.
     */
    public static final int KEY_LIMIT = 256;

    private static final String PREFIX = "vexelray: ";
    private static final String OVERFLOW_KEY = "Diagnostics/keyLimit";

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final List<String> RECORDED = new CopyOnWriteArrayList<>();

    private Diagnostics() {
    }

    /**
     * Report that {@code what} was asked for and not done, and say {@code why}. The first call for a given
     * {@code key} prints to {@code System.err}; later ones with the same key do nothing.
     *
     * <p>Both halves earn their place. {@code what} is what the consumer thought they were getting, in their
     * vocabulary rather than the seam's; {@code why} is the reason, because "unsupported" sends a reader to the
     * wrong file. A message that says only the first is a message that gets read as a warning about nothing.
     *
     * @param key  a stable identifier for this call site — see the class note on data-derived keys
     * @param what the capability that was requested and dropped
     * @param why  the reason, specific enough to act on
     */
    public static void dropped(String key, String what, String why) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a diagnostic needs a key to be warned once by");
        }
        if (SEEN.size() >= KEY_LIMIT && !SEEN.contains(key)) {
            overflow(key);
            return;
        }
        if (!SEEN.add(key)) {
            return;
        }
        emit(what + " — " + why);
    }

    /** Reported once when the key limit is reached, because a data-derived key is itself worth saying. */
    private static void overflow(String key) {
        if (SEEN.add(OVERFLOW_KEY)) {
            emit("diagnostics stopped remembering keys at " + KEY_LIMIT + " (first dropped: '" + key
                    + "') — a key should identify a call site, not the data flowing through it");
        }
    }

    private static void emit(String message) {
        RECORDED.add(message);
        if (!"off".equalsIgnoreCase(System.getProperty("vexelray.diag", "on"))) {
            System.err.println(PREFIX + message);
        }
    }

    /**
     * Every message emitted since the last {@link #reset()}, in order — the half that makes these testable.
     *
     * <p>Populated whether or not printing is silenced, so a suite that runs with {@code -Dvexelray.diag=off}
     * to keep its output clean still proves the warnings happen.
     */
    public static List<String> recorded() {
        return List.copyOf(RECORDED);
    }

    /** Forget every key and message. For tests, which need each case to start from silence. */
    public static void reset() {
        SEEN.clear();
        RECORDED.clear();
    }
}
