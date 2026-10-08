package dev.vexelray.msdf;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which faces a family bakes, and every way of asking for one that has to be refused with a reason. */
class FamilyPlanTest {

    private static FamilyConfig family(String name, File... sources) {
        FamilyConfig cfg = new FamilyConfig();
        cfg.name = name;
        cfg.sources = List.of(sources);
        return cfg;
    }

    @Test
    void aDirectoryOfOneFamilyNeedsNothingElse(@TempDir Path dir) throws Exception {
        Files.copy(SfntFontTest.SANS, dir.resolve("NotoSans-Regular.ttf"));

        FamilyPlan plan = FamilyPlan.of(family("sans", dir.toFile()));

        assertEquals("Noto Sans", plan.fontFamily);
        assertEquals(1, plan.faces.size());
        assertEquals("regular", plan.faces.get(0).key());
        assertNull(plan.faces.get(0).instance());
        assertEquals("-font", plan.faces.get(0).fontArguments().get(0));
    }

    @Test
    void severalFamiliesInTheSourcesMustBeChosenBetween() throws Exception {
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> FamilyPlan.of(family("sans", SfntFontTest.FONTS.toFile())));
        assertTrue(e.getMessage().contains("'Noto Sans'") && e.getMessage().contains("'Noto Sans Mono'"),
                e.getMessage());
        assertTrue(e.getMessage().contains("<match>"), e.getMessage());

        FamilyConfig mono = family("mono", SfntFontTest.FONTS.toFile());
        mono.match = "noto sans mono";
        assertEquals("Noto Sans Mono", FamilyPlan.of(mono).fontFamily);
    }

    @Test
    void aStyleThatIsNotThereIsRefusedWithTheOnesThatAre() {
        FamilyConfig cfg = family("sans", SfntFontTest.SANS.toFile());
        cfg.styles = "regular, bold";
        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> FamilyPlan.of(cfg));
        assertTrue(e.getMessage().contains("[bold]") && e.getMessage().contains("[regular]"), e.getMessage());
    }

    @Test
    void twoFilesThatAreTheSameFaceAreRefusedByName(@TempDir Path dir) throws Exception {
        Files.copy(SfntFontTest.SANS, dir.resolve("a.ttf"));
        Files.copy(SfntFontTest.SANS, dir.resolve("b.ttf"));
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> FamilyPlan.of(family("sans", dir.toFile())));
        assertTrue(e.getMessage().contains("a.ttf") && e.getMessage().contains("b.ttf"), e.getMessage());
    }

    @Test
    void aNameIsADirectorySoItMustBeOne() {
        assertThrows(MojoExecutionException.class,
                () -> FamilyPlan.of(family("../sans", SfntFontTest.SANS.toFile())));
    }

    /** The default charset is the font's whole repertoire, less what never draws and the synthesized box. */
    @Test
    void theDefaultCharsetIsEveryMappedCodePoint() throws Exception {
        FamilyPlan.Face face = FamilyPlan.of(family("sans", SfntFontTest.SANS.toFile())).faces.get(0);
        String charset = FamilyPlan.charsetOf(face, null);

        assertTrue(charset.startsWith("[0x20, 0x7E]"), charset.substring(0, 40));
        assertFalse(charset.contains("0xFFFD"));
        int covered = 0;
        for (String line : charset.split("\\R")) {
            String[] ends = line.replaceAll("[\\[\\]\\s]", "").split(",");
            covered += Integer.decode(ends[1]) - Integer.decode(ends[0]) + 1;
        }
        int expected = (int) face.font().codepoints().stream()
                .filter(cp -> cp >= 0x20 && !(cp >= 0x7F && cp < 0xA0) && cp != 0xFFFD).count();
        assertEquals(expected, covered);

        assertEquals("[0x20, 0x7E]\n", FamilyPlan.charsetOf(face, "ascii"), "a charset narrows");
    }
}
