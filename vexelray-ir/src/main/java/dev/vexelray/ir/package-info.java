/**
 * The shared vocabulary for authoring SupirVast {@code core} IR by hand.
 *
 * <p>{@link dev.vexelray.ir.Ir} is a single class of static helpers — constants, vector construction and
 * component access, arithmetic, math calls — plus the two pieces of {@code core}'s type discipline that every
 * caller otherwise rediscovers: a binary operator's operands must share a type (scalars broadcast), and a zero
 * has to be built at a specific type.
 *
 * <p>It is a module of its own because it is the layer <em>below</em> everything that emits a shader — surfaces,
 * shading models, the 2D canvas, the research harness — and because it had already been copied twice before it
 * was one. It depends on nothing but the IR it writes.
 *
 * <p>Expression-level only. Statements, regions, functions, and entry points belong to whoever is composing a
 * shader; this package knows nothing about shaders.
 */
package dev.vexelray.ir;
