package dev.vexelray.technique.sdf;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.surface.Surface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cone field's buffer carries geometry and nothing else, so a painted surface renders in one colour. The
 * march was always going to do that; what it did not do was say so, and a plot in one colour reads as a plot
 * that is dim rather than as a capability that was declined.
 *
 * <p>These watch the warning fire. The existing {@link ConeFieldTest} proves the SPIR-V is valid and correctly
 * packed and could not have caught this, because the shader that drops the colour is a perfectly good shader.
 */
class ConeFieldColourWarningTest {

    @BeforeEach
    void silence() {
        Diagnostics.reset();
    }

    private static Surface.Stroke stroke(boolean painted) {
        List<Surface.Stroke.Vertex> through = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Surface.Stroke.Vertex v = Surface.Stroke.Vertex.sharp(i * 0.1 - 0.5, 0.2, 0, 0.05);
            through.add(painted ? v.painted(new Surface.Rgb(1, 0.25, i / 3.0)) : v);
        }
        return new Surface.Stroke(through, 4);
    }

    @Test
    void aPaintedSurfaceIsReportedAsColourThisTechniqueCannotRender() {
        ConeField.compose(SdfScene.of(stroke(true)));
        List<String> recorded = Diagnostics.recorded();
        assertEquals(1, recorded.size(), "the dropped capability must be reported");
        assertTrue(recorded.get(0).contains("per-vertex colour"));
        assertTrue(recorded.get(0).contains("single albedo"),
                "and must say what the picture will actually look like, since it will look deliberate");
    }

    @Test
    void anUnpaintedSurfaceSaysNothing() {
        ConeField.compose(SdfScene.of(stroke(false)));
        assertEquals(List.of(), Diagnostics.recorded(),
                "the ordinary case is every cone field; a diagnostic here would be noise");
    }

    @Test
    void composingTwiceWarnsOnce() {
        ConeField.compose(SdfScene.of(stroke(true)));
        ConeField.compose(SdfScene.of(stroke(true)));
        assertEquals(1, Diagnostics.recorded().size());
    }

    @Test
    void theShadersAreStillProducedWhateverTheWarningSays() {
        assertEquals(2, ConeField.compose(SdfScene.of(stroke(true))).size(),
                "a diagnostic that changed what was rendered would be a worse fault than the one it reports");
    }
}
