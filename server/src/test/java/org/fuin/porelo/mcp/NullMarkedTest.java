package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

class NullMarkedTest {
    // Prevents new main packages from bypassing NullAway checks.
    @Test
    void everyMainCodePackageHasACheckedNullContract() throws Exception {
        Path root =
                Path.of(
                        McpSession.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        var packages = new HashSet<Package>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name =
                        root.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                packages.add(
                        Class.forName(name.substring(0, name.length() - ".class".length()))
                                .getPackage());
            }
        }
        assertThat(packages.stream().map(Package::getName))
                .as("the guard must cover the program's one package")
                .containsExactly("org.fuin.porelo.mcp");
        for (Package candidate : packages) {
            assertThat(candidate.isAnnotationPresent(NullMarked.class))
                    .as("%s must be checked by NullAway", candidate.getName())
                    .isTrue();
        }
    }
}
