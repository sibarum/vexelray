package dev.vexelray.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A trace site: the method writes a row on the probe's {@code trace} lane each time it runs, naming itself and the
 * arguments {@link #args} names, inside whatever cause it runs in. See {@code docs/trace.md}.
 *
 * <pre>{@code
 * @Trace(value = "seek", args = {"position"})
 * void seek(long position) { ... }
 * }</pre>
 *
 * <p>Nothing happens until {@code vexelray-trace-maven-plugin} weaves the compiled class: the annotation is read
 * from the class file at build time, never reflected on, so it is retained in the class and not at run time. A build
 * without the weaver runs the method untouched.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Trace {

    /** The site's label, and its row's kind ({@code trace.<label>}). Empty: the method's name. */
    String value() default "";

    /**
     * Parameters to record, by name, at most four. Each must be a primitive, an enum (recorded as its ordinal) or a
     * {@code String} (recorded as its length, or in full with {@link #content}). The weaver refuses anything else:
     * recording an object would keep it alive and record state that may change after the row is written.
     */
    String[] args() default {};

    /**
     * The site's id, pinned. Zero: a hash of the class, the method and its descriptor, as {@code serialVersionUID}
     * is when none is declared, which changes when they do. Pin it for a site something outside the build watches.
     */
    long id() default 0L;

    /** Record the {@code String} arguments in full, one {@code trace.text} row each, rather than by length. */
    boolean content() default false;

    /** Also write a {@code trace.exit} row as the method returns or throws, with how long it took. */
    boolean timed() default false;
}
