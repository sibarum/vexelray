package dev.vexelray.technique.sdf;

import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Surface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The invariants of the projection convention that hold without a GPU.
 *
 * <p>Small, and here rather than in {@code vexelray-shader}, because the claim worth pinning is a
 * relationship between two modules: {@link ClipDepth}'s far plane and {@link MarchSettings}'s. Their agreeing
 * is what stops a ray that gave up marching from landing at a depth some geometry could also have occupied,
 * and {@code ClipDepth}'s own module cannot see the march to check it.
 */
class ClipDepthTest {

    private static SdfScene scene(MarchSettings march, double nearPlane) {
        return new SdfScene(new Surface.Sphere(0, 0, 5, 1), Shadings.unlit(), march,
                new Surface.Rgb(1, 1, 1), new Surface.Rgb(0, 0, 0), 1.4, nearPlane);
    }

    /**
     * A scene's clip depth takes its far plane from the march, whatever the march says.
     *
     * <p>Not a restatement of the derivation: it is the reason {@code SdfScene} derives instead of storing.
     * A stored far plane would be correct on the day it was written and wrong the first time someone called
     * {@link SdfScene#withMarch}, which is a change that has nothing visibly to do with depth.
     */
    @Test
    void theClipDepthFarPlaneFollowsTheMarch() {
        assertEquals(MarchSettings.DEFAULT.farPlane(),
                scene(MarchSettings.DEFAULT, 0.05).clipDepth().far());

        MarchSettings shortRange = new MarchSettings(64, 0.4, 30.0, 0.008, 0.001, 0.03, 0.006);
        assertEquals(30.0, scene(shortRange, 0.05).clipDepth().far());
        assertEquals(30.0, scene(MarchSettings.DEFAULT, 0.05).withMarch(shortRange).clipDepth().far());
    }

    /** The default scene's convention is the default convention — the claim {@code ClipDepth.DEFAULT} makes. */
    @Test
    void theDefaultsAgreeAcrossTheTwoModules() {
        assertEquals(ClipDepth.DEFAULT, SdfScene.of(new Surface.Sphere(0, 0, 5, 1)).clipDepth());
        assertEquals(ClipDepth.DEFAULT.far(), MarchSettings.DEFAULT.farPlane());
    }

    @Test
    void aDegenerateRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ClipDepth(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new ClipDepth(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> new ClipDepth(100, 100));
        assertThrows(IllegalArgumentException.class, () -> new ClipDepth(100, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClipDepth(1, Double.POSITIVE_INFINITY));
    }

    /**
     * A near plane at or beyond the march's far plane is refused by the scene, not by {@link ClipDepth}.
     *
     * <p>Where it has to be: the two numbers live in different objects, and the scene is the first place that
     * holds both. Left unchecked, the failure would surface as {@code ClipDepth}'s own complaint at the
     * moment a shader was composed — pointing at a record the caller never constructed.
     */
    @Test
    void aNearPlaneBeyondTheMarchIsRefusedByTheScene() {
        assertThrows(IllegalArgumentException.class,
                () -> scene(MarchSettings.DEFAULT, MarchSettings.DEFAULT.farPlane()));
        assertThrows(IllegalArgumentException.class,
                () -> scene(MarchSettings.DEFAULT, MarchSettings.DEFAULT.farPlane() + 1));
        assertThrows(IllegalArgumentException.class, () -> scene(MarchSettings.DEFAULT, 0));
    }
}
