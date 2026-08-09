package dev.vexelray.text;

/**
 * Atlas-wide parameters from the msdf-atlas-gen JSON header.
 *
 * @param type          e.g. {@code "msdf"}
 * @param distanceRange SDF distance range in atlas pixels (the {@code pxrange}); drives the fragment AA
 * @param emSize        font em size in atlas pixels (the atlas {@code size}, e.g. 32)
 * @param width         atlas image width in pixels
 * @param height        atlas image height in pixels
 * @param yOriginBottom {@code true} if the atlas Y origin is at the bottom (msdf-atlas-gen default)
 */
public record AtlasInfo(String type, float distanceRange, float emSize,
                        int width, int height, boolean yOriginBottom) {
}
