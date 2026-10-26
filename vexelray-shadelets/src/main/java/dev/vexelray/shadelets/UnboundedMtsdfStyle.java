package dev.vexelray.shadelets;

import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * An {@link MtsdfStyle} field read past its own cell: a fragment anywhere on the plane is answered. Inside the trusted
 * disc the field is read as it is; past it the sample is pulled in toward the cell's middle along a soft knee that never
 * leaves the disc, and the distance the pull took away is added back. Far from any shape the lines of equal distance
 * are circles round it and the distance grows along the radius, so the further out, the truer the answer.
 *
 * <p>The cell is {@code (cellU0, cellV0)-(cellU1, cellV1)} in texture coordinates; lengths along it are in shares of
 * its side, which is square in texels whatever the texture's shape.
 *
 * @param near      as {@link MtsdfStyle#near()}
 * @param cellU0    the field cell's left, in texture coordinates
 * @param cellV0    its top
 * @param cellU1    its right
 * @param cellV1    its bottom
 * @param knee      where the pull starts, from the cell's middle, in shares of its side
 * @param soft      how far past {@code knee} a pulled sample can reach, the same way: {@code knee + soft} is the edge of
 *                  the trusted disc
 * @param cellRange the cell's side in units of the field's value: {@code side / (2 * spread)}
 */
@Style
public record UnboundedMtsdfStyle(float near,
                                  @PerInstance float cellU0, @PerInstance float cellV0,
                                  @PerInstance float cellU1, @PerInstance float cellV1,
                                  @PerInstance float knee, @PerInstance float soft,
                                  @PerInstance float cellRange) {
}
