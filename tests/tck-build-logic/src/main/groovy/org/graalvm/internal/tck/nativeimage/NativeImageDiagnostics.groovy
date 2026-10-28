/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.internal.tck.nativeimage

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.graalvm.buildtools.gradle.tasks.NativeRunTask

/// Owns tracing, profiling, and their test-only arguments. §FS-tests.3, §AR-test-harness.4
class NativeImageDiagnostics {
    final Project project
    final String libraryGAV
    final String metadataPath
    final Closure<String> nativeImageTool
    final GraalVMExtension nativeExtension
    final SourceSetContainer sourceSets
    boolean generateDynamicAccessReport
    boolean nativeTraceImageTaskRequested
    boolean runNativeTraceImageTaskRequested
    boolean nativeTestTaskRequested
    boolean nativeTestPGOSamplingTaskRequested
    String pgoSamplingPeriodMicros
    final Provider<List<String>> nativeTraceBuildArgs
    final Provider<List<String>> pgoBuildArgs

    NativeImageDiagnostics(Project project, String libraryGAV, String metadataPath,
                           Closure<String> nativeImageTool, TestedLibraryArtifacts artifacts,
                           NativeImageLayers layers) {
        this.project = project
        this.libraryGAV = libraryGAV
        this.metadataPath = metadataPath
        this.nativeImageTool = nativeImageTool
        nativeExtension = project.extensions.getByType(GraalVMExtension)
        sourceSets = project.extensions.getByType(SourceSetContainer)
        String generateDynamicAccessReportVal = project.providers.gradleProperty('tck.generateDynamicAccessReport').getOrElse("false")
        boolean generateDynamicAccessReportTaskRequested = project.gradle.startParameter.taskNames.any { taskName ->
            taskName == "generateDynamicAccessReport" || taskName.endsWith(":generateDynamicAccessReport")
        }
        generateDynamicAccessReport = generateDynamicAccessReportVal.toBoolean() || generateDynamicAccessReportTaskRequested
        boolean informationalTaskRequested = isTaskRequested("help") || isTaskRequested("tasks") || isTaskRequested("properties")
        nativeTraceImageTaskRequested = !informationalTaskRequested &&
                (isTaskRequested("nativeTraceImage") || isTaskRequested("runNativeTraceImage"))
        runNativeTraceImageTaskRequested = !informationalTaskRequested && isTaskRequested("runNativeTraceImage")
        nativeTestTaskRequested = !informationalTaskRequested &&
                (isTaskRequested("nativeTest") || isTaskRequested("nativeTestCompile"))
        // §AR-test-harness.8, §forge/AR-code-coverage-deep-navigation.1.1: deep-path guidance
        // needs an instrumented, sampling image plus the analysis call-tree CSV graph;
        // JaCoCo alone decides coverage.
        nativeTestPGOSamplingTaskRequested = !informationalTaskRequested &&
                (isTaskRequested("nativeTestPGOSampling") || isTaskRequested("runNativeTestPGO"))
        // Native Image requires a sampling period of at least 100 microseconds.
        pgoSamplingPeriodMicros = project.providers.gradleProperty("pgoSamplingPeriodMicros").getOrElse("100")
        if (runNativeTraceImageTaskRequested) {
            requiredAbsolutePathProperty("traceMetadataPath")
        }
        if (nativeTraceImageTaskRequested && project.hasProperty("metadataConfigDirs")) {
            requiredDirectoryListProperty("metadataConfigDirs", false)
        }
        Provider<Directory> dynamicAccessOutputDir = project.layout.buildDirectory.dir("native/nativeTestCompile/dynamic-access")

        project.tasks.register("generateDynamicAccessReport") { task ->
            task.setDescription("Builds native tests with dynamic-access tracking and reuses Gradle up-to-date checks when inputs and classpath are unchanged")
            task.setGroup(JavaBasePlugin.VERIFICATION_GROUP)
            task.dependsOn(project.tasks.named("nativeTestCompile"))
            // JARs resolve from the Gradle cache where filenames are content-addressed, so name-only is sufficient.
            task.inputs.files(project.providers.provider { artifacts.resolveTestedLibraryJars() })
                    .withPropertyName("trackedLibraryJars")
                    .withPathSensitivity(PathSensitivity.NAME_ONLY)
            task.inputs.files(sourceSets.test.output)
                    .withPropertyName("testOutputs")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            // metadataPath is scoped to the active coordinate via GVM_TCK_MD / -Pmetadata.dir, not the repo-wide metadata root.
            task.inputs.files(project.fileTree(metadataPath) { include("**/*.json") })
                    .withPropertyName("metadataFiles")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            task.inputs.file(layers.ciJsonFile)
                    .withPropertyName("ciJsonFile")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            task.inputs.property("nativeImageArgs", project.providers.provider { layers.nativeImageArgs })
            task.outputs.dir(dynamicAccessOutputDir)
                    .withPropertyName("dynamicAccessOutputDir")
        }
        nativeTraceBuildArgs = project.providers.provider {
            List<String> buildArgs = []
            if (nativeTraceImageTaskRequested) {
                buildArgs.add("-H:+UnlockExperimentalVMOptions")
                buildArgs.add("-H:+MetadataTracingSupport")
                buildArgs.add("-H:-UnlockExperimentalVMOptions")
            }
            List<String> metadataConfigDirs = commaSeparatedGradleProperty("metadataConfigDirs")
            boolean metadataConfigDirsRequested = project.hasProperty("metadataConfigDirs") &&
                    (nativeTraceImageTaskRequested || nativeTestTaskRequested)
            if (metadataConfigDirsRequested && !metadataConfigDirs.isEmpty()) {
                buildArgs.add("-H:ConfigurationFileDirectories=${metadataConfigDirs.join(",")}")
            }
            if (runNativeTraceImageTaskRequested) {
                // The verification gate runs runNativeTraceImage in its correction
                // loop and routes on the binary's exit code. Bake exact-metadata
                // reporting + Exit-on-missing into the trace binary so the gate can
                // observe ExitStatus.MISSING_METADATA (172) for a metadata gap and
                // distinguish it from other test failures, while still collecting
                // trace metadata in the same run.
                buildArgs.add("--exact-reachability-metadata")
                buildArgs.add("-H:MissingRegistrationReportingMode=Exit")
            }
            return buildArgs
        }

        pgoBuildArgs = project.providers.provider {
            List<String> buildArgs = []
            if (nativeTestPGOSamplingTaskRequested) {
                if (!pgoSamplingPeriodMicros.matches("[1-9][0-9]*")) {
                    throw new GradleException(
                            "'pgoSamplingPeriodMicros' must be a positive integer, got '${pgoSamplingPeriodMicros}'."
                    )
                }

                // Profiles are guidance-only evidence: sampled stacks show where execution
                // already runs, and instrumented counters record every branch successor and
                // virtual-call receiver the run took. Neither proves non-execution (JaCoCo is
                // the coverage metric). PrintAnalysisCallTreeType=CSV dumps
                // reports/call_tree_{methods,invokes,targets}_*.csv as the static call graph.
                buildArgs.add("--pgo-instrument")
                buildArgs.add("--pgo-sampling")
                buildArgs.add("-H:+UnlockExperimentalVMOptions")
                // Released GraalVMs sample at a fixed period and reject the option, so it is
                // passed only where native-image lists it; a non-default period the
                // toolchain cannot honour fails the build (§AR-test-harness).
                String expertOptions = project.providers.exec {
                    commandLine(nativeImageTool("native-image"), "--expert-options-all")
                    setIgnoreExitValue(true)
                }.standardOutput.asText.get()
                if (expertOptions.contains("PGOSamplingPeriodMicros")) {
                    buildArgs.add("-H:PGOSamplingPeriodMicros=${pgoSamplingPeriodMicros}")
                } else if (pgoSamplingPeriodMicros != "100") {
                    throw new GradleException(
                            "The selected native-image does not support 'pgoSamplingPeriodMicros'; " +
                                    "it samples at a fixed period, so a period of ${pgoSamplingPeriodMicros} cannot be applied."
                    )
                }
                buildArgs.add("-H:+PrintAnalysisCallTree")
                buildArgs.add("-H:PrintAnalysisCallTreeType=CSV")
                buildArgs.add("-H:-UnlockExperimentalVMOptions")
            }
            return buildArgs
        }
        project.tasks.named("nativeTestCompile", BuildNativeImageTask).configure { BuildNativeImageTask task ->
            task.doFirst {
                File nativeImageOutputDirectory = task.outputFile.get().asFile.parentFile
                if (!nativeImageOutputDirectory.isDirectory() && !nativeImageOutputDirectory.mkdirs()) {
                    throw new GradleException("Failed to create native-image output directory: ${nativeImageOutputDirectory.absolutePath}")
                }
                if (runNativeTraceImageTaskRequested) {
                    requiredGradleProperty("traceMetadataPath")
                }
            }
        }

        project.tasks.register("nativeTraceImage") { task ->
            task.setDescription("Builds the test native image with runtime metadata tracing support enabled")
            task.setGroup(LifecycleBasePlugin.BUILD_GROUP)
            task.dependsOn(project.tasks.named("nativeTestCompile"))
        }

        project.tasks.register("runNativeTraceImage", Exec) { Exec task ->
            task.setDescription("Runs the trace-enabled test native image once and writes runtime metadata trace output")
            task.setGroup(JavaBasePlugin.VERIFICATION_GROUP)
            task.dependsOn(project.tasks.named("nativeTraceImage"))
            task.ignoreExitValue = true
            task.doFirst {
                String traceMetadataPath = requiredAbsolutePathProperty("traceMetadataPath")
                String traceMetadataConditionPackages = deriveTraceMetadataConditionPackages()
                BuildNativeImageTask nativeTestCompile = project.tasks.named("nativeTestCompile", BuildNativeImageTask).get()
                NativeRunTask nativeTest = project.tasks.named("nativeTest", NativeRunTask).get()
                File imageFile = nativeTestCompile.outputFile.get().asFile
                List<String> runtimeArgs = []
                runtimeArgs.addAll(nativeTest.internalRuntimeArgs.get())
                runtimeArgs.addAll(nativeTest.runtimeArgs.get())
                runtimeArgs.add("-XX:TraceMetadata=path=${traceMetadataPath}")
                runtimeArgs.add("-XX:TraceMetadataConditionPackages=${traceMetadataConditionPackages}")
                task.executable = imageFile.absolutePath
                task.args = runtimeArgs
            }
            task.doLast {
                // When -PtraceBinaryExitFile=<absolute path> is supplied, write the
                // binary's exit code to that file. The verification gate reads this
                // sentinel to recover ExitStatus.MISSING_METADATA (172) without
                // grepping Gradle's log output.
                String exitFilePath = project.providers.gradleProperty("traceBinaryExitFile").getOrElse("")
                if (!exitFilePath.isBlank()) {
                    File exitFile = project.file(exitFilePath)
                    File parent = exitFile.parentFile
                    if (parent != null) {
                        parent.mkdirs()
                    }
                    exitFile.text = String.valueOf(task.executionResult.get().exitValue)
                }
            }
        }

        project.tasks.register("mergeNativeTraceMetadata", Exec) { Exec task ->
            task.setDescription("Merges runtime metadata trace output directories with native-image-utils generate")
            task.setGroup("Metadata")
            task.doFirst {
                List<File> inputDirs = requiredDirectoryListProperty("inputDirs", false)
                File outputDir = project.file(requiredAbsolutePathProperty("outputDir"))
                project.delete(outputDir)
                List<String> commandLineArgs = [nativeImageTool("native-image-utils"), "generate"]
                commandLineArgs.addAll(inputDirs.collect { File inputDir -> "--input-dir=${inputDir.absolutePath}" })
                commandLineArgs.add("--output-dir=${outputDir.absolutePath}")
                task.commandLine(commandLineArgs)
            }
        }
        project.tasks.register("nativeTestPGOSampling") { task ->
            task.inputs.property("pgoSamplingPeriodMicros", pgoSamplingPeriodMicros)
            task.setDescription("Builds the test native image with PGO instrumentation, sampling, and the analysis call-tree CSV dump enabled")
            task.setGroup(LifecycleBasePlugin.BUILD_GROUP)
            task.dependsOn(project.tasks.named("nativeTestCompile"))
        }

        project.tasks.register("runNativeTestPGO", Exec) { Exec task ->
            task.setDescription("Runs the profiled test native image once and writes the .iprof profile")
            task.setGroup(JavaBasePlugin.VERIFICATION_GROUP)
            task.dependsOn(project.tasks.named("nativeTestPGOSampling"))
            task.doFirst {
                String pgoProfilePath = requiredAbsolutePathProperty("pgoProfilePath")
                BuildNativeImageTask nativeTestCompile = project.tasks.named("nativeTestCompile", BuildNativeImageTask).get()
                NativeRunTask nativeTest = project.tasks.named("nativeTest", NativeRunTask).get()
                File imageFile = nativeTestCompile.outputFile.get().asFile
                List<String> runtimeArgs = []
                runtimeArgs.addAll(nativeTest.internalRuntimeArgs.get())
                runtimeArgs.addAll(nativeTest.runtimeArgs.get())
                // PGO images dump the profile on exit; pin the output path so the
                // analyzer reads a known location instead of a working-directory default.iprof.
                runtimeArgs.add("-XX:ProfilesDumpFile=${pgoProfilePath}")
                task.executable = imageFile.absolutePath
                task.args = runtimeArgs
            }
        }
    }

    Boolean isTaskRequested(String taskName) {
        project.gradle.startParameter.taskNames.any { String requestedTaskName ->
            requestedTaskName == taskName || requestedTaskName.endsWith(":${taskName}")
        }
    }
    String requiredGradleProperty(String propertyName) {
        String propertyValue = project.providers.gradleProperty(propertyName).getOrElse("")
        if (propertyValue.trim().isEmpty()) {
            throw new GradleException("Missing '${propertyName}' property! Rerun Gradle with '-P${propertyName}=<value>'")
        }
        return propertyValue
    }

    List<String> commaSeparatedGradleProperty(String propertyName) {
        String propertyValue = project.providers.gradleProperty(propertyName).getOrElse("")
        if (propertyValue.trim().isEmpty()) {
            return []
        }
        return propertyValue.split(",")
                .collect { String value -> value.trim() }
                .findAll { String value -> !value.isEmpty() }
    }

    List<File> requiredDirectoryListProperty(String propertyName, boolean requireAbsolute) {
        List<String> propertyValues = commaSeparatedGradleProperty(propertyName)
        if (propertyValues.isEmpty()) {
            requiredGradleProperty(propertyName)
        }
        return propertyValues.collect { String propertyValue ->
            File directory = project.file(propertyValue)
            if (requireAbsolute && !new File(propertyValue).isAbsolute()) {
                throw new GradleException("Property '${propertyName}' must contain absolute paths. Got: ${propertyValue}")
            }
            if (!directory.isDirectory()) {
                throw new GradleException("Property '${propertyName}' must contain existing directories. Missing: ${directory.absolutePath}")
            }
            return directory
        }
    }

    String requiredAbsolutePathProperty(String propertyName) {
        String propertyValue = requiredGradleProperty(propertyName)
        if (!new File(propertyValue).isAbsolute()) {
            throw new GradleException("Property '${propertyName}' must contain an absolute path. Got: ${propertyValue}")
        }
        return propertyValue
    }
    String deriveTraceMetadataConditionPackages() {
        String explicitConditionPackages = project.providers.gradleProperty("traceMetadataConditionPackages").getOrElse("").trim()
        if (!explicitConditionPackages.isBlank()) {
            return explicitConditionPackages
        }

        String userCodeFilterPath = nativeExtension.agent.modes.conditional.userCodeFilterPath.getOrElse("user-code-filter.json")
        File userCodeFilterFile = project.file(userCodeFilterPath)
        if (!userCodeFilterFile.isFile()) {
            String fallbackPackage = libraryGAV.split(":", 2)[0]
            project.logger.warn("Cannot derive TraceMetadataConditionPackages for {}: {} does not exist. Falling back to {}.", libraryGAV, userCodeFilterPath, fallbackPackage)
            return fallbackPackage
        }

        def parsed = new JsonSlurper().parse(userCodeFilterFile)
        def rules = parsed instanceof Map ? parsed.rules : null
        if (!(rules instanceof List)) {
            throw new GradleException("Cannot derive TraceMetadataConditionPackages for ${libraryGAV}: ${userCodeFilterPath} must contain a rules array.")
        }

        List<String> packages = []
        rules.each { rule ->
            if (!(rule instanceof Map) || !(rule.includeClasses instanceof String)) {
                return
            }
            String includeClasses = rule.includeClasses.trim()
            if (includeClasses.isBlank() || includeClasses == "**") {
                return
            }
            String packageName = includeClasses
            if (packageName.endsWith(".**")) {
                packageName = packageName.substring(0, packageName.length() - 3)
            } else if (packageName.endsWith(".*")) {
                packageName = packageName.substring(0, packageName.length() - 2)
            } else if (packageName.contains(".")) {
                packageName = packageName.substring(0, packageName.lastIndexOf("."))
            }
            if (!packageName.isBlank() && !packages.contains(packageName)) {
                packages.add(packageName)
            }
        }

        if (packages.isEmpty()) {
            throw new GradleException("Cannot derive TraceMetadataConditionPackages for ${libraryGAV}: ${userCodeFilterPath} has no includeClasses package rules.")
        }
        return packages.join(",")
    }
}
