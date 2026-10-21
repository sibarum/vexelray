package dev.vexelray.os.linux;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The per-user data folder, as the XDG base directory rule has it and as {@code vexelray-installer}'s
 * {@code install.sh} resolves it: {@code ${XDG_DATA_HOME:-$HOME/.local/share}}.
 */
class UserDataDirTest {

    @Test
    void xdgDataHomeWhenItIsSet() {
        assertEquals(Path.of("/data/ann"),
                LinuxPlatform.userDataDir(Map.of("XDG_DATA_HOME", "/data/ann")::get, "/home/ann"));
    }

    @Test
    void theHomesLocalShareWhenItIsNot() {
        assertEquals(Path.of("/home/ann/.local/share"),
                LinuxPlatform.userDataDir(Map.<String, String>of()::get, "/home/ann"), "unset");
        assertEquals(Path.of("/home/ann/.local/share"),
                LinuxPlatform.userDataDir(Map.of("XDG_DATA_HOME", "")::get, "/home/ann"), "empty, as :- treats it");
        assertEquals(Path.of("/home/ann/.local/share"),
                LinuxPlatform.userDataDir(Map.of("XDG_DATA_HOME", " ")::get, "/home/ann"), "blank");
    }

    @Test
    void windowsVariablesMeanNothingHere() {
        assertEquals(Path.of("/home/ann/.local/share"), LinuxPlatform.userDataDir(
                Map.of("LOCALAPPDATA", "C:/Users/ann/AppData/Local")::get, "/home/ann"));
    }
}
