package dev.vexelray.experimental;

/**
 * The measured result of running one {@link ShapeField} through the {@link ComparisonHarness} — the row of the
 * comparison table. Performance is split into shader-compose cost, shader size, cold render time, and CPU-eval
 * cost (the render==sim side); fidelity is the image error of a cheap render against a high-step reference of the
 * same field; applicability is the field's own qualitative note.
 *
 * @param name              the field's name
 * @param composeMillis     time to build the {@code core} IR and lower it to SPIR-V
 * @param spirvBytes        size of the fragment SPIR-V (a proxy for shader complexity)
 * @param renderMedianMillis median cold render time (includes pipeline+shader-module build, per the harness note)
 * @param cpuNanosPerCall   time to evaluate the field once on the CPU (Truffle) — the collision/query cost
 * @param fidelityRmse      RMSE (0..255) of the candidate render vs a high-step reference render of the same field;
 *                          lower means the cheap render has converged closer to ground truth
 * @param applicability     the field's qualitative applicability note
 */
public record Metrics(String name, double composeMillis, int spirvBytes, double renderMedianMillis,
                      double cpuNanosPerCall, double fidelityRmse, String applicability) {
}
