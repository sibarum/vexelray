package dev.vexelray.msdf;

import dev.vexelray.msdf.FaceStyle.Slope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FaceStyleTest {

    @Test
    void theDefaultsAreLeftOut() {
        assertEquals("regular", new FaceStyle(400, 5, Slope.NORMAL).key());
        assertEquals("bold", new FaceStyle(700, 5, Slope.NORMAL).key());
        assertEquals("italic", new FaceStyle(400, 5, Slope.ITALIC).key());
        assertEquals("bold-italic", new FaceStyle(700, 5, Slope.ITALIC).key());
        assertEquals("condensed-light-oblique", new FaceStyle(300, 3, Slope.OBLIQUE).key());
        assertEquals("semicondensed", new FaceStyle(400, 4, Slope.NORMAL).key());
    }

    /** A variable font can name an instance between the conventional weights; it must not take regular's key. */
    @Test
    void anUnconventionalWeightKeepsItsNumber() {
        assertEquals("semilight", new FaceStyle(350, 5, Slope.NORMAL).key());
        assertEquals("450", new FaceStyle(450, 5, Slope.NORMAL).key());
    }

    @Test
    void aWidthAxisPercentageLandsOnTheNearestClass() {
        assertEquals(5, FaceStyle.stretchOf(100f));
        assertEquals(3, FaceStyle.stretchOf(75f));
        assertEquals(4, FaceStyle.stretchOf(85f));
        assertEquals(1, FaceStyle.stretchOf(40f));
        assertEquals(9, FaceStyle.stretchOf(300f));
    }
}
