package dev.vexelray.text;

/** An axis-aligned rectangle by its four edges. Used for both em-space plane bounds and atlas-pixel bounds. */
public record Rect(float left, float bottom, float right, float top) {
}
