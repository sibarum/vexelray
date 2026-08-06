package dev.vexelray.os;

/**
 * The operating-system families VexelRay targets. This enum is the <em>only</em> place OS identity is modelled;
 * no code elsewhere inspects {@code os.name}. Build-time module selection (Maven OS profiles) and runtime
 * provider selection ({@link NativePlatform#current()}) key off the platform module present, not off string
 * sniffing. See {@code docs/native-bindings.md}.
 */
public enum Platform {
    WINDOWS,
    LINUX,
    MACOS
}
