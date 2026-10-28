/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.internal.tck.harness;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/// Exercises the post-compilation hook for each selected base. §FS-tests.3.5
class LayerRuntimeLibrariesTests {
    @TempDir
    Path tempDir;

    @Test
    void packagesTheCommonSharedBasesLibraries() throws IOException {
        assertPackagesSelectedBase("shared", false, "");
    }

    @Test
    void packagesTheCustomSharedBasesLibraries() throws IOException {
        assertPackagesSelectedBase("custom", false, "test { buildArgs.add('-H:+AddAllCharsets') }");
    }

    @Test
    void packagesTheDedicatedBasesLibraries() throws IOException {
        assertPackagesSelectedBase("dedicated", true, "");
    }

    private void assertPackagesSelectedBase(String mode, boolean dedicated, String binaries) throws IOException {
        Path project = LayerBuildOptionsTestFixture.create(tempDir, binaries, dedicated);
        Files.writeString(project.resolve("build.gradle"), """

                tasks.register('stageFixtureLibraries') {
                    doLast {
                        File commonDirectory = file(providers.gradleProperty('tck.baseLayerFile').get()).parentFile
                        commonDirectory.mkdirs()
                        new File(commonDirectory, 'libawt.so').text = 'unused common base'
                        File selectedDirectory = commonDirectory
                        if (!providers.gradleProperty('tck.dedicatedLayer').get().toBoolean()) {
                            String layerUse = graalvmNative.binaries.test.buildArgs.get()
                                .find { String argument -> argument.startsWith('-H:LayerUse=') }
                            selectedDirectory = file(layerUse.substring('-H:LayerUse='.length())).parentFile
                        }
                        selectedDirectory.mkdirs()
                        new File(selectedDirectory, 'libawt.so').text = '%s'
                        new File(selectedDirectory, 'libjvm.so').text = 'base shim'
                        new File(selectedDirectory, 'base-layer.nil').text = 'layer archive'
                        new File(selectedDirectory, 'manifest.json').text = '{}'

                        def compileTask = tasks.named('nativeTestCompile').get()
                        compileTask.outputDirectory.set(layout.buildDirectory.dir('native/fixtureImage'))
                        File imageDirectory = compileTask.outputDirectory.get().asFile
                        imageDirectory.mkdirs()
                        new File(imageDirectory, 'libjvm.so').text = 'application shim'
                        new File(imageDirectory, 'test-binary').text = 'compiled executable'
                        // Simulate successful compilation and execute the real packaging hook.
                        compileTask.actions.last().execute(compileTask)
                    }
                }
                """.formatted(mode), StandardOpenOption.APPEND);

        GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("stageFixtureLibraries", "--offline", "--quiet").build();

        Path image = project.resolve("build/native/fixtureImage");
        assertThat(Files.readString(image.resolve("libawt.so"))).isEqualTo(mode);
        assertThat(Files.readString(image.resolve("libjvm.so"))).isEqualTo("application shim");
        assertThat(Files.readString(image.resolve("test-binary"))).isEqualTo("compiled executable");
        assertThat(image.resolve("base-layer.nil")).doesNotExist();
        assertThat(image.resolve("manifest.json")).doesNotExist();
    }
}
