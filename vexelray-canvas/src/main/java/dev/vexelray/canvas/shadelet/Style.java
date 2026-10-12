package dev.vexelray.canvas.shadelet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record as a shadelet's style: its components are the shadelet's parameters, in order. The shadelet
 * processor ({@code vexelray-shadelet-codegen}) generates, beside it, {@code <Record>Layout}: the record's
 * {@link StyleLayout}, and the writers that put a value of it into the style table and an element's record. Nothing
 * reflects; the annotation is gone after compilation.
 *
 * <p>A component is a {@code float} (one float) or a {@link dev.vexelray.canvas.Color} (four: r, g, b, a). One marked
 * {@link PerInstance} is in the element's record rather than the style table.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Style {
}
