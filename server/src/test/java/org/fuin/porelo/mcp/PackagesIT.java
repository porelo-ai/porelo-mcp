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

    /** Each library the executable may link, with the DEB and the RPM name that brings it. */
    private static final Map<String, List<String>> DECLARED =
            Map.of(
                    "libc.so.6", List.of("libc6 (>= ", "libc.so.6(GLIBC_"),
                    "libz.so.1", List.of("zlib1g (>= 1:", "libz.so.1(ZLIB_"));

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
    void theExecutableAsksForNoCpuFeatureBeyondTheX8664Baseline() throws IOException {
        String image =
                new String(
                        Files.readAllBytes(Path.of(System.getProperty("mcp.executable"))),
                        StandardCharsets.ISO_8859_1);

        Matcher required =
                Pattern.compile("required by the image: \\[([A-Z0-9_, ]+)\\]").matcher(image);

        assertThat(required.find()).as("the image's own start-up check").isTrue();
        assertThat(required.group(1)).as("the x86-64 baseline only").isEqualTo(BASELINE);
    }

    // Harm: an executable linking a library neither package brings, so it fails to start once
    // installed.
    @Test
    void everyLibraryTheExecutableLinksIsDeclaredByBothPackagesAtItsMeasuredFloor()
            throws Exception {
        String executable = System.getProperty("mcp.executable");
        String needed = run("objdump", "-p", executable);
        String symbols = run("objdump", "-T", executable);
        String depends = run("dpkg-deb", "-f", deb().toString(), "Depends");
        String requires = run("rpm", "-qp", "--requires", rpm().toString());

        Matcher libraries = Pattern.compile("NEEDED\\s+(\\S+)").matcher(needed);
        List<String> linked = new ArrayList<>();
        while (libraries.find()) {
            linked.add(libraries.group(1));
        }
        assertThat(linked)
                .as("the libraries it links, each one with a name the packages declare")
                .isNotEmpty()
                .allSatisfy(
                        library ->
                                assertThat(DECLARED)
                                        .as("a library %s the packages know", library)
                                        .containsKey(library));
        String glibc = newestVersion(symbols, "GLIBC_");
        String zlib = newestVersion(symbols, "ZLIB_");
        assertThat(depends)
                .as("the DEB's floors")
                .contains("libc6 (>= " + glibc + ")", "zlib1g (>= 1:" + zlib + ")");
        assertThat(requires)
                .as("the RPM's floors")
                .contains(
                        "libc.so.6(GLIBC_" + glibc + ")(64bit)",
                        "libz.so.1(ZLIB_" + zlib + ")(64bit)");
    }

    // Harm: a package published under a version that is not the build's, so an upgrade skips or
    // repeats it.
    @Test
    void bothPackagesCarryTheProjectsVersionAsThePackagingMapsIt() throws Exception {
        String project =
                javax.xml.xpath.XPathFactory.newInstance()
                        .newXPath()
                        .evaluate(
                                "/project/parent/version",
                                javax.xml.parsers.DocumentBuilderFactory.newInstance()
                                        .newDocumentBuilder()
                                        .parse(Path.of("pom.xml").toFile()));
        boolean snapshot = project.endsWith("-SNAPSHOT");
        String version = project.replaceFirst("-SNAPSHOT$", "");
        // Worked out here from the raw parts, never read back from deb.version or rpm.release.
        String run = System.getProperty("porelo.snapshot.run");
        String suffix = System.getProperty("porelo.snapshot.suffix", "");
        assertThat(run).as("the run number the build passes").isNotNull();

        String debVersion = run("dpkg-deb", "-f", deb().toString(), "Version").strip();
        String rpmSaid =
                run("rpm", "-qp", "--queryformat", "\n%{VERSION} %{RELEASE}", rpm().toString());

        assertThat(debVersion)
                .as("the DEB's version")
                .isEqualTo(snapshot ? version + "~snapshot." + run + suffix : version);
        assertThat(rpmSaid.substring(rpmSaid.lastIndexOf('\n') + 1).strip())
                .as("the RPM's version and release")
                .isEqualTo(version + " " + (snapshot ? "snapshot." + run : "1"));
        assertThat(run("dpkg-deb", "-c", deb().toString()))
                .as("the DEB installs it as /usr/bin/porelo-mcp")
                .contains("./usr/bin/porelo-mcp");
        assertThat(run("rpm", "-qlp", rpm().toString()))
                .as("the RPM installs it as /usr/bin/porelo-mcp")
                .contains("/usr/bin/porelo-mcp");
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
        return newest(TARGET.resolve("rpm"), "porelo-mcp-", ".rpm");
    }

    /** The newest package whose file name starts so: the build may leave older ones beside it. */
    private static Path newest(Path dir, String prefix, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
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

    /** The highest version of a symbol prefix in objdump's output, compared number by number. */
    private static String newestVersion(String symbols, String prefix) {
        Matcher m = Pattern.compile(prefix + "([0-9]+(?:\\.[0-9]+)*)").matcher(symbols);
        String newest = null;
        while (m.find()) {
            if (newest == null || compare(m.group(1), newest) > 0) {
                newest = m.group(1);
            }
        }
        assertThat(newest).as("the executable needs some %s symbol", prefix).isNotNull();
        return newest;
    }

    private static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d =
                    Integer.compare(
                            i < x.length ? Integer.parseInt(x[i]) : 0,
                            i < y.length ? Integer.parseInt(y[i]) : 0);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }
}
