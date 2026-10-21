package dev.vexelray.os.windows;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The per-user data folder: {@code %LOCALAPPDATA%}, which is where {@code install.ps1} keeps its records. */
class UserDataDirTest {

    @Test
    void localAppDataWhenItIsSet() {
        assertEquals(Path.of("C:/Users/ann/AppData/Local"), WindowsPlatform.userDataDir(
                Map.of("LOCALAPPDATA", "C:/Users/ann/AppData/Local")::get, "C:/Users/ann"));
    }

    @Test
    void appDataLocalUnderTheHomeWhenItIsNot() {
        assertEquals(Path.of("C:/Users/ann", "AppData", "Local"),
                WindowsPlatform.userDataDir(Map.<String, String>of()::get, "C:/Users/ann"), "unset");
        assertEquals(Path.of("C:/Users/ann", "AppData", "Local"),
                WindowsPlatform.userDataDir(Map.of("LOCALAPPDATA", " ")::get, "C:/Users/ann"), "blank");
    }

    @Test
    void xdgVariablesMeanNothingHere() {
        assertEquals(Path.of("C:/Users/ann", "AppData", "Local"),
                WindowsPlatform.userDataDir(Map.of("XDG_DATA_HOME", "/data/ann")::get, "C:/Users/ann"));
    }
}
