package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.PushConstants;

/**
 * Where the shader reads a parameter from — the one thing the surface layer does <em>not</em> decide.
 *
 * <p>{@link SurfaceCompiler} knows that a {@link Scalar.Param} lowers to a read; it does not know whether that
 * read is a push constant, a storage buffer, or something a later stage invents. Whoever composes the shader
 * knows, because it is the same object that declares the block and writes the bytes. So the compiler is handed
 * one of these and asks it.
 *
 * <p>Obtain one from {@link ParamBlock}, which is what holds the slot assignment: the store's job is only to
 * turn a slot into an expression.
 */
public interface ParamStore {

    /**
     * The expression reading {@code id}'s current value.
     *
     * @throws IllegalArgumentException by name if this store was not built for that parameter
     */
    Expr read(ParamId id);

    /**
     * Whether {@code block} is the push-constant block this store issued.
     *
     * <p>Asked of every {@link Surface.Implicit}, which may contain arbitrary IR and therefore a read of a
     * push-constant block the composer never declared. Such a read does not fail on its own — the module emits
     * one block, the foreign one's members are never part of it, and only the member <em>index</em> survives, so
     * a read of member 0 silently becomes a read of the camera's {@code camX}. Refusing it by name is the whole
     * of R1.3.
     *
     * <p>Default {@code false}: a store that is not backed by push constants at all has issued no block, so
     * every push-constant read inside an implicit is foreign to it.
     */
    default boolean issued(PushConstants block) {
        return false;
    }

    /**
     * The store for a surface compiled without one: every parameter is an error, and it says which.
     *
     * <p>This is what {@link SurfaceCompiler#compile(Surface)} uses, so that a parametric surface reaching a
     * caller that never arranged for values fails at the parameter rather than rendering something arbitrary.
     */
    ParamStore NONE = new ParamStore() {

        @Override
        public Expr read(ParamId id) {
            throw new IllegalArgumentException(
                    "surface has a parameter (" + id + ") but was compiled without a parameter block; compile "
                            + "through SdfComposer, or pass a ParamStore from ParamBlock");
        }

        @Override
        public String toString() {
            return "ParamStore.NONE";
        }
    };
}
