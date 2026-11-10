package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packages the build made, read and unpacked as a machine would: what they hold, what they
 * declare, and whether the executable in each starts where it is installed.
 */
class PackagesIT {

    private static final Path TARGET = Path.of("target");

    /** Each library the executable may link, with the DEB's dependency that brings it. */
    private static final Map<String, String> DECLARED =
            Map.of(
                    "libc.so.6", "libc6 (>= " + System.getProperty("mcp.glibc.floor") + ")",
                    "libz.so.1", "zlib1g (>= 1:" + System.getProperty("mcp.zlib.floor") + ")");

    /** What x86-64 itself guarantees: nothing beyond it may be asked for. */
    private static final String BASELINE = "CX8, CMOV, FXSR, MMX, SSE, SSE2";

    @TempDir Path directory;

    // Harm: a package that installs an executable which cannot run there, for want of a variable or
    // a library.
    @Test
    void
            theExecutableFromEachPackageRunsWithAnEmptyEnvironmentAndExitsThreeNamingTheRuntimeDirectory()
                    throws Exception {
        for (Path executable : List.of(fromDeb(), fromRpm())) {
            ProcessBuilder builder =
                    new ProcessBuilder(executable.toString(), "--check").redirectErrorStream(true);
            builder.environment().clear();
            Process started = builder.start();
            String said =
                    new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(started.waitFor()).as("%s: %s", executable, said).isEqualTo(3);
            assertThat(said).as("names what is missing").contains("XDG_RUNTIME_DIR");
        }
    }

    // Harm: a package whose executable starts but cannot reach a daemon, or reports another's
    // methods.
    @Test
    void theExecutableFromEachPackageReachesADaemonAndNamesItsVersionAndMethods() throws Exception {
        String description = StandInDaemon.tasks1();
        try (var daemon = new StandInDaemon(directory, description, Map.of())) {
            for (Path executable : List.of(fromDeb(), fromRpm())) {
                Process started =
                        new ProcessBuilder(
                                        executable.toString(),
                                        "--check",
                                        "--daemon-socket",
                                        daemon.socket().toString())
                                .start();
                String out =
                        new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                assertThat(started.waitFor()).as("%s reached the daemon", executable).isEqualTo(0);
                List<String> lines = new ArrayList<>(out.lines().toList());
                assertThat(lines.remove(0)).as("the version").contains("9.9.9-standin");
                assertThat(lines)
                        .as("every method")
                        .containsExactlyElementsOf(
                                ContractToolsTest.methodsDeclaredIn(description));
            }
        }
    }

    // Harm: an executable that starts on the machine that built it and refuses an older one.
    @Test
    void theExecutableFromEachPackageAsksForNoCpuFeatureBeyondTheX8664Baseline() throws Exception {
        for (Path executable : List.of(fromDeb(), fromRpm())) {
            String image = new String(Files.readAllBytes(executable), StandardCharsets.ISO_8859_1);

            Matcher required =
                    Pattern.compile("required by the image: \\[([A-Z0-9_, ]+)\\]").matcher(image);

            assertThat(required.find())
                    .as("%s: the image's own start-up check", executable)
                    .isTrue();
            assertThat(required.group(1))
                    .as("%s: the x86-64 baseline only", executable)
                    .isEqualTo(BASELINE);
        }
    }

    // Harm: a DEB that installs where the executable it carries cannot start, or that no machine
    // can install.
    @Test
    void theDebDependsOnExactlyTheLibrariesItsExecutableLinksAtTheDeclaredFloors()
            throws Exception {
        Path executable = fromDeb();
        Matcher libraries =
                Pattern.compile("NEEDED\\s+(\\S+)")
                        .matcher(run("objdump", "-p", executable.toString()));
        List<String> expected = new ArrayList<>();
        while (libraries.find()) {
            String library = libraries.group(1);
            assertThat(DECLARED)
                    .as("a library %s the DEB knows a package for", library)
                    .containsKey(library);
            expected.add(DECLARED.get(library));
        }

        String depends = run("dpkg-deb", "-f", deb().toString(), "Depends").strip();

        assertThat(expected).as("the executable links some library").isNotEmpty();
        assertThat(List.of(depends.split(",\\s*")))
                .as("the DEB's Depends, one entry per linked library")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    // Harm: a package another name, another machine, or no bill and license, so it is not the one
    // published.
    @Test
    void bothPackagesAreNamedForThisProgramOnX8664AndCarryItsBillAndLicense() throws Exception {
        String debFields = run("dpkg-deb", "-f", deb().toString(), "Package", "Architecture");
        String rpmFields =
                run("rpm", "-qp", "--queryformat", "\n%{NAME} %{ARCH}", rpm().toString());
        String debFiles = run("dpkg-deb", "-c", deb().toString());
        String rpmFiles = run("rpm", "-qlp", rpm().toString());

        assertThat(debFields)
                .as("the DEB's name and machine")
                .contains("Package: porelo-mcp", "Architecture: amd64");
        assertThat(rpmFields.substring(rpmFields.lastIndexOf('\n') + 1).strip())
                .as("the RPM's name and machine")
                .isEqualTo("porelo-mcp x86_64");
        assertThat(debFiles)
                .as("the DEB's executable, bill and license")
                .contains(
                        "./usr/bin/porelo-mcp",
                        "./usr/share/doc/porelo-mcp/sbom.cdx.json",
                        "./usr/share/doc/porelo-mcp/copyright");
        assertThat(rpmFiles)
                .as("the RPM's executable and bill")
                .contains("/usr/bin/porelo-mcp", "/usr/share/doc/porelo-mcp/sbom.cdx.json");
    }

    // Harm: a package that ships third-party code without the license texts that code asks to
    // travel with it.
    @Test
    void bothPackagesShipTheNoticesOfEveryComponentInTheBillAndOfTheRuntime() throws Exception {
        Path unpacked = Files.createTempDirectory(directory, "deb");
        run("dpkg-deb", "-x", deb().toString(), unpacked.toString());
        Path doc = unpacked.resolve("usr/share/doc/porelo-mcp");
        String notices = Files.readString(doc.resolve("THIRD-PARTY-NOTICES"));
        String licenses = run("rpm", "-qLp", rpm().toString());

        assertThat(Files.readString(doc.resolve("copyright")))
                .as("the DEB's copyright names the notices")
                .contains("/usr/share/doc/porelo-mcp/THIRD-PARTY-NOTICES");
        assertThat(billedComponents()).as("the bill names the compiled-in jars").isNotEmpty();
        for (String component : billedComponents()) {
            assertThat(notices)
                    .as("a section for %s, from the bill", component)
                    .contains("\n" + component + "\n");
        }
        assertThat(notices).as("a section for the runtime").contains("\nGraalVM runtime ");
        assertThat(licenses.lines().toList())
                .as("the RPM's %license files")
                .contains(
                        "/usr/share/licenses/porelo-mcp/LICENSE",
                        "/usr/share/licenses/porelo-mcp/THIRD-PARTY-NOTICES");
    }

    // Harm: an RPM that installs where the executable it carries cannot start.
    @Test
    void theRpmRequiresTheCLibraryAndZlibAtTheFloorsTheExecutableNeeds() throws Exception {
        List<String> requires = run("rpm", "-qRp", rpm().toString()).lines().toList();

        assertThat(requires)
                .as("the RPM's requirements")
                .contains(
                        "libc.so.6(GLIBC_" + System.getProperty("mcp.glibc.floor") + ")(64bit)",
                        "libz.so.1(ZLIB_" + System.getProperty("mcp.zlib.floor") + ")(64bit)");
    }

    /** Each component of the bill but the program itself, as {@code group:artifact:version}. */
    private static List<String> billedComponents() throws IOException {
        Matcher purl =
                Pattern.compile("\"purl\"\\s*:\\s*\"pkg:maven/([^/\"]+)/([^@\"]+)@([^?\"]+)")
                        .matcher(Files.readString(TARGET.resolve("sbom.cdx.json")));
        List<String> components = new ArrayList<>();
        while (purl.find()) {
            if (!purl.group(2).equals("porelo-mcp")) {
                components.add(purl.group(1) + ":" + purl.group(2) + ":" + purl.group(3));
            }
        }
        return components;
    }

    private Path fromDeb() throws Exception {
        Path into = Files.createTempDirectory(directory, "deb");
        run("dpkg-deb", "-x", deb().toString(), into.toString());
        return into.resolve("usr/bin/porelo-mcp");
    }

    private Path fromRpm() throws Exception {
        Path into = Files.createTempDirectory(directory, "rpm");
        run(
                "sh",
                "-c",
                "rpm2cpio \"$1\" | (cd \"$2\" && cpio -idm --quiet)",
                "sh",
                rpm().toAbsolutePath().toString(),
                into.toString());
        return into.resolve("usr/bin/porelo-mcp");
    }

    static Path deb() throws IOException {
        return newest(TARGET, "porelo-mcp_", ".deb");
    }

    static Path rpm() throws IOException {
        return newest(TARGET, "porelo-mcp-", ".rpm");
    }

    /** The newest package whose file name starts so: the build may leave older ones beside it. */
    private static Path newest(Path dir, String prefix, String suffix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(
                            f ->
                                    f.getFileName().toString().startsWith(prefix)
                                            && f.toString().endsWith(suffix))
                    .max(Comparator.comparing(f -> f.toFile().lastModified()))
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "no " + prefix + "*" + suffix + " under " + dir));
        }
    }

    static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s: %s", List.of(command), output).isZero();
        return output;
    }
}
