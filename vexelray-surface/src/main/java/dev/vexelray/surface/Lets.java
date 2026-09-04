package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Names a value so it can be used more than once without being computed more than once — the remedy
 * docs/surface-compiler.md §4.1 keeps arriving at, finally applied inside the compiler itself.
 *
 * <p>It exists because of a specific failure, worth recording rather than summarising. Selecting a colour out of
 * a union means asking which child is nearer, which means naming both children's distances. Folded pairwise
 * against the <em>accumulated</em> field, the k-th comparison embeds the whole chain of the k-1 before it, so the
 * colour expression grows as the square of the child count while the distance grows linearly. Measured on a
 * stroke: 114 cones lowered to 54,663 nodes of distance and <b>9,406,276</b> nodes of colour — 172x — which
 * reached the driver as 21 MB of SPIR-V and locked the machine compiling it.
 *
 * <p>Binding turns that multiplication into addition. Each child's distance is named once, each combination
 * refers to two names, and the whole colour program comes out the same order of size as the field it selects
 * over.
 *
 * <p>Statements accumulate in <b>post-order</b>, which is what makes the list valid as a program: the compiler
 * binds a subtree's values before the combination that uses them, so every read refers to a declaration already
 * emitted. Nothing here reorders, and nothing may.
 */
final class Lets {

    private final List<Statement> statements = new ArrayList<>();
    private int next;

    /** Bind {@code value} to a fresh local and return a read of it. */
    Expr bind(String name, Expr value) {
        LocalVar variable = new LocalVar(name + "_" + next++, value.type());
        statements.add(new Statement.DeclareVar(variable, value));
        return new Expr.Read(variable);
    }

    /** The declarations, in the order they must be emitted. */
    List<Statement> statements() {
        return List.copyOf(statements);
    }

    /** Whether anything was bound at all — false for every surface that named no colour. */
    boolean isEmpty() {
        return statements.isEmpty();
    }
}
