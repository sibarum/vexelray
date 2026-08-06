package dev.vexelray.pipeline;

import java.util.List;
import java.util.Optional;

/**
 * A fullscreen image operation: samples one or more input attachments and writes a colour target. Tone-mapping
 * an HDR intermediate down to the swapchain, blur, or compositing are all post passes. Composes into a fullscreen
 * fragment shader (SupirVast {@code Fullscreen}) whose body is the named operation.
 *
 * @param name      unique pass name
 * @param reads     the input attachments sampled (must be non-empty — a post pass transforms something)
 * @param writes    the colour target written (typically the swapchain for the final pass)
 * @param operation identifier of the fullscreen operation to compose (e.g. {@code "tonemap-aces"}, {@code "blur"})
 */
public record PostPass(String name, List<String> reads, List<String> writes, String operation) implements Pass {

    public PostPass {
        reads = List.copyOf(reads);
        writes = List.copyOf(writes);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pass name must be non-blank");
        }
        if (reads.isEmpty()) {
            throw new IllegalArgumentException("a post pass must read at least one input attachment");
        }
        if (writes.isEmpty()) {
            throw new IllegalArgumentException("a post pass must write a colour target");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must be non-blank");
        }
    }

    @Override
    public Optional<String> depth() {
        return Optional.empty();
    }

    @Override
    public PassKind kind() {
        return PassKind.POST;
    }
}
