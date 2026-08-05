package dev.alex.threadium.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ThreadiumConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripPreservesEverySupportedRuntimeValue() throws Exception {
        Path path = temporaryDirectory.resolve("threadium.properties");
        ThreadiumConfig expected = new ThreadiumConfig(
                true,
                false,
                true,
                true,
                32,
                "opengl33",
                4096,
                96,
                65536,
                131072,
                196608,
                256,
                134217728L,
                false,
                false,
                60);

        assertTrue(ThreadiumConfig.save(path, expected));
        assertEquals(expected, ThreadiumConfig.load(path));
    }

    @Test
    void unknownPropertiesSurviveSave() throws Exception {
        Path path = temporaryDirectory.resolve("threadium.properties");
        Files.writeString(path, "third.party.extension=preserve-me\n");

        assertTrue(ThreadiumConfig.save(path, ThreadiumConfig.defaults()));
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        assertEquals("preserve-me", properties.getProperty("third.party.extension"));
    }

    @Test
    void malformedOrOutOfRangeValuesFailClosedToDefaults() throws Exception {
        Path path = temporaryDirectory.resolve("threadium.properties");
        Files.writeString(path, "enabled=true\nentity.gpu.maxInstances=-1\n");

        ThreadiumConfig loaded = ThreadiumConfig.load(path);
        assertEquals(ThreadiumConfig.defaults(), loaded);
        assertFalse(loaded.enabled());
    }

    @Test
    void legacySystemPropertiesRemainExplicitOverrides() {
        String replacement = System.getProperty("threadium.backport1211.replacement");
        String gpu = System.getProperty("threadium.backport1211.gpu");
        try {
            System.setProperty("threadium.backport1211.replacement", "true");
            System.setProperty("threadium.backport1211.gpu", "true");
            ThreadiumConfig defaults = ThreadiumConfig.defaults();

            assertTrue(defaults.replacementEnabled());
            assertTrue(defaults.gpuReplacementEnabled());
        } finally {
            restore("threadium.backport1211.replacement", replacement);
            restore("threadium.backport1211.gpu", gpu);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
