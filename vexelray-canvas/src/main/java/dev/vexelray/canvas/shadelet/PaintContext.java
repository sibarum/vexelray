package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;

import java.util.List;

/**
 * What a painter is handed: the fragment, the distance at it, and the means to ask for the distance elsewhere.
 *
 * <p>{@link #distanceAt} calls the function the composition generates for the element's source and modifiers. There
 * is no handing a painter its source as a value (vastir has no function values, SPIR-V no function pointers), so
 * the one generated function dispatches on the look instead, and every painter calls it.
 */
public final class PaintContext {

    private final Fragment fragment;
    private final Expr distance;
    private final Function distanceAt;
    private final Expr look;
    private final Expr element;

    PaintContext(Fragment fragment, Expr distance, Function distanceAt, Expr look, Expr element) {
        this.fragment = fragment;
        this.distance = distance;
        this.distanceAt = distanceAt;
        this.look = look;
        this.element = element;
    }

    public Fragment fragment() {
        return fragment;
    }

    /** The {@code vec2(distance, reach)} at the fragment, after the look's modifiers. */
    public Expr distance() {
        return distance;
    }

    /**
     * The {@code vec2(distance, reach)} at the fragment moved by {@code offset} ({@code vec2}, canvas units), after the
     * look's modifiers. Past the reach it means nothing: a painter that looks elsewhere fades its layer out before
     * the distance it reads reaches the reach.
     */
    public Expr distanceAt(Expr offset) {
        return new Expr.Call(distanceAt, List.of(offset, fragment.aa(), fragment.pixelsPerUnit(), look, element));
    }
}
