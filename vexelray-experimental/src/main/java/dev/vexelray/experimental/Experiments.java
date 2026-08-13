package dev.vexelray.experimental;

import dev.vexelray.experimental.fields.BlendedPrimitivesField;
import dev.vexelray.experimental.fields.FlatPlaneField;
import dev.vexelray.experimental.fields.PerlinAnalyticField;
import dev.vexelray.experimental.fields.PerlinField;
import dev.vexelray.experimental.fields.ValueNoiseField;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for the technique bake-off. Registers the {@link ShapeField} candidates, runs them through the
 * {@link ComparisonHarness}, and writes captures + a montage + a report under {@code target/experiments/}.
 *
 * <p>Run: {@code mvn -pl vexelray-experimental -am compile exec:exec} (needs {@code --enable-native-access}). Add
 * candidates by implementing {@link ShapeField} and adding them to {@link #FIELDS}. The comparison isolates the
 * shape definition — every field is rendered through the identical {@link Raymarcher}.
 */
public final class Experiments {

    /** The techniques under comparison. Add new {@link ShapeField}s here. */
    private static final List<ShapeField> FIELDS = List.of(
            new FlatPlaneField(),
            new ValueNoiseField(),
            new PerlinField(),
            new PerlinAnalyticField(),
            new BlendedPrimitivesField());

    public static void main(String[] args) {
        Path outDir = Path.of("target", "experiments");
        List<Metrics> results = ComparisonHarness.standard().run(FIELDS, outDir);
        System.out.println("compared " + results.size() + " techniques -> " + outDir.toAbsolutePath());
    }

    private Experiments() {
    }
}
