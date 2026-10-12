package dev.vexelray.canvas.shadelet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Style} component as per instance: written into each element's record when the element is drawn,
 * rather than shared in the style table. For a value that differs element by element or animates: a morph's
 * {@code t}, a reveal's progress. Changing it is this annotation, not a shader edit: the shadelet reads the field the
 * same way either way.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface PerInstance {
}
