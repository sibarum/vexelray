package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;

import java.util.List;

/**
 * One piece of the canvas's ubershader: a source of distance, a modifier of it, or a painter of it. An application
 * composes the shadelets it draws with into its shader ({@link Composition}); an element names the ones that draw it
 * in its {@link Look}.
 *
 * <p>Distances are signed, <b>positive inside</b>, in canvas units, carried as {@code vec2(distance, reach)}:
 * {@code reach} is how far from the edge the distance can be trusted. Everything a shadelet reads arrives through
 * the {@link Fragment} and its {@link StyleView}; it reads no stage variable of its own.
 */
public sealed interface Shadelet permits Shadelet.Source, Shadelet.Modifier, Shadelet.Painter {

    /** Unique in a composition and stable across builds: the manifest and a look name a shadelet by it. */
    String name();

    /** Its parameters. */
    default StyleLayout style() {
        return StyleLayout.NONE;
    }

    /** Functions its code calls, added to the module once however many shadelets name them. */
    default List<Function> helpers() {
        return List.of();
    }

    /**
     * How far past an element's geometry this draws, in canvas units, for its shared parameters as the style table
     * holds them. The canvas grows the element's quad by the largest reach in its look.
     */
    default float reach(float[] shared) {
        return 0f;
    }

    /** Produces the distance. */
    non-sealed interface Source extends Shadelet {
        /**
         * The {@code vec2(distance, reach)} at the fragment moved by {@code offset} canvas units. A painter that looks
         * elsewhere (a shadow below, a bevel toward the light) asks for it through {@link PaintContext#distanceAt}.
         */
        Expr distance(Fragment fragment, Expr offset, StyleView style);
    }

    /** Reshapes the distance. */
    non-sealed interface Modifier extends Shadelet {
        /** The new {@code vec2(distance, reach)}. Moving the edge out by some amount takes as much from reach. */
        Expr modify(Expr distance, Fragment fragment, StyleView style);
    }

    /** Turns the distance into a layer of colour. */
    non-sealed interface Painter extends Shadelet {
        /**
         * Statements that leave this painter's layer in {@code layer}: a {@code vec4}, <b>premultiplied</b>. Layers
         * are composited front to back, in the look's order. Locals a painter declares are its own.
         */
        Region paint(PaintContext context, StyleView style, LocalVar layer);
    }
}
