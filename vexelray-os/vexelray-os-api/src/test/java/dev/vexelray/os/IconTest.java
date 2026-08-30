package dev.vexelray.os;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parts of {@link Icon} that are pure data: which size answers a request, and whether the pixels stay put.
 * Nothing here opens a window — realising an icon is a platform's job and is checked by the Win32 smoke.
 */
class IconTest {

    private static Icon.Image image(int size) {
        return new Icon.Image(size, size, new int[size * size]);
    }

    @Test
    void exactSizeWins() {
        Icon icon = Icon.of(image(16), image(32), image(256));
        assertEquals(16, icon.bestFor(16).width());
        assertEquals(32, icon.bestFor(32).width());
        assertEquals(256, icon.bestFor(256).width());
    }

    @Test
    void nearestSizeAnswersWhenNoneIsExact() {
        Icon icon = Icon.of(image(16), image(64));
        assertEquals(16, icon.bestFor(20).width(), "20 is nearer 16 than 64");
        assertEquals(64, icon.bestFor(48).width(), "48 is nearer 64 than 16");
    }

    @Test
    void aTieGoesToTheLargerImage() {
        // 24 is equidistant from 16 and 32. Scaling down loses less than scaling up, so 32 is the better answer.
        Icon icon = Icon.of(image(16), image(32));
        assertEquals(32, icon.bestFor(24).width());
    }

    @Test
    void aSingleSizeAnswersEveryRequest() {
        Icon icon = Icon.of(4, 4, new int[16]);
        assertEquals(4, icon.bestFor(256).width());
        assertEquals(4, icon.bestFor(1).width());
    }

    @Test
    void pixelsAreCopiedOnTheWayInAndOut() {
        int[] source = {0xFF112233, 0x00000000, 0xFFFFFFFF, 0x80808080};
        Icon.Image held = new Icon.Image(2, 2, source);

        source[0] = 0;   // a later write to the caller's array must not reach the icon
        assertEquals(0xFF112233, held.argb()[0]);

        int[] handedOut = held.argb();
        assertNotSame(handedOut, held.argb());
        handedOut[1] = 0xDEADBEEF;   // nor a write to what it hands out
        assertArrayEquals(new int[]{0xFF112233, 0x00000000, 0xFFFFFFFF, 0x80808080}, held.argb());
    }

    @Test
    void aPixelCountThatDoesNotMatchTheSizeIsRefused() {
        // Silently accepting this reads a wrong number of pixels into native memory later, where the failure is
        // a corrupt icon or a crash rather than a message naming the mistake.
        assertThrows(IllegalArgumentException.class, () -> new Icon.Image(16, 16, new int[16]));
    }

    @Test
    void anIconNeedsAtLeastOneSize() {
        assertThrows(IllegalArgumentException.class, Icon::of);
    }

    @Test
    void twoImagesOfTheSameSizeAreRefused() {
        // bestFor would have to pick one arbitrarily, and which one it picked would be invisible to the caller.
        assertThrows(IllegalArgumentException.class, () -> Icon.of(image(32), image(32)));
    }

    @Test
    void aWindowConfigCarriesNoIconUntilOneIsAskedFor() {
        WindowConfig plain = WindowConfig.of("t", 100, 100);
        assertEquals(null, plain.icon());

        Icon icon = Icon.of(image(32));
        assertEquals(icon, plain.icon(icon).icon());
    }

    @Test
    void theIconSurvivesTheOtherBuilders() {
        // Every wither rebuilds the record, so each is a chance to drop a component silently.
        Icon icon = Icon.of(image(32));
        WindowConfig config = WindowConfig.of("t", 100, 100)
                .icon(icon)
                .at(10, 20)
                .minSize(50, 50)
                .decorations(Decorations.CLIENT)
                .ownedBy(7L);
        assertEquals(icon, config.icon());
        assertEquals(10, config.x());
        assertEquals(50, config.minWidth());
        assertEquals(Decorations.CLIENT, config.decorations());
        assertEquals(7L, config.owner());
    }
}
