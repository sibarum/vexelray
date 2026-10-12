package dev.vexelray.shadelets;

import dev.vexelray.canvas.shadelet.Style;

/**
 * How much bolder ({@code weight > 0}) or lighter a shape is drawn: its edge moved outward by {@code weight} canvas
 * units. For a text-like weight change on an icon, or a box that swells as it is pressed.
 *
 * @param weight canvas units the edge moves out; negative moves it in
 */
@Style
public record WeightStyle(float weight) {
}
