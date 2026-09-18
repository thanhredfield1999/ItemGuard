package com.itemguard.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PremiumSlf4jProviderContractTest {
    @Test
    void premiumJarSuppliesARelocatedSafeSlf4jServiceProvider() throws Exception {
        Path descriptor = Path.of(
            "src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider");
        String provider = Files.readString(descriptor).replace("\r\n", "\n").trim();
        String pom = Files.readString(Path.of("pom.xml"));

        assertEquals("org.slf4j.helpers.NOP_FallbackServiceProvider", provider);
        assertTrue(pom.contains("ServicesResourceTransformer"),
            "shade must relocate the provider class name in the service descriptor");
    }
}
