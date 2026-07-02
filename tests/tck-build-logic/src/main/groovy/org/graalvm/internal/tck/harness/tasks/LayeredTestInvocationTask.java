/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.internal.tck.harness.tasks;

import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task that runs native tests with the shared Native Image base layer.
 * <p>
 * Implements §TCK-test-harness.3 — the LayerUse test lane.
 */
@SuppressWarnings("unused")
public abstract class LayeredTestInvocationTask extends TestInvocationTask {
    private static final String CONTINUE_ON_COORDINATE_FAILURE_PROPERTY = "tck.layered.continueOnCoordinateFailure";
    private static final String COORDINATE_FAILURE_REPORT_PROPERTY = "tck.layered.coordinateFailureReport";
    private static final String EXCLUDED_COORDINATES_FILE_PROPERTY = "tck.layered.excludedCoordinatesFile";

    @Override
    protected List<String> resolveCoordinates() {
        Set<String> excludedCoordinates = excludedCoordinates();
        if (excludedCoordinates.isEmpty()) {
            return super.resolveCoordinates();
        }

        List<String> coordinates = super.resolveCoordinates();
        List<String> includedCoordinates = coordinates.stream()
                .filter(coordinate -> !excludedCoordinates.contains(coordinate))
                .collect(Collectors.toList());
        int excludedCount = coordinates.size() - includedCoordinates.size();
        if (excludedCount > 0) {
            getLogger().lifecycle("Excluded {} known layered-test failure(s).", excludedCount);
        }
        return includedCoordinates;
    }

    @Override
    public List<String> commandFor(String coordinates) {
        List<String> command = super.commandFor(coordinates);
        appendBaseLayerFileProperty(command);
        return command;
    }

    @Override
    protected String errorMessageFor(String coordinates, int exitCode) {
        return "Layered test for " + coordinates + " failed with exit code " + exitCode + ".";
    }

    @Override
    protected boolean continueOnCoordinateFailure() {
        Object continueOnFailure = getProject().findProperty(CONTINUE_ON_COORDINATE_FAILURE_PROPERTY);
        return Boolean.parseBoolean(Objects.toString(continueOnFailure, "false"));
    }

    @Override
    protected File coordinateFailureReportFile() {
        Object reportPath = getProject().findProperty(COORDINATE_FAILURE_REPORT_PROPERTY);
        if (reportPath == null) {
            return null;
        }
        return getProject().file(reportPath.toString());
    }

    private Set<String> excludedCoordinates() {
        Object exclusionsPath = getProject().findProperty(EXCLUDED_COORDINATES_FILE_PROPERTY);
        if (exclusionsPath == null || exclusionsPath.toString().isBlank()) {
            return Collections.emptySet();
        }

        File exclusionsFile = getProject().file(exclusionsPath.toString());
        if (!exclusionsFile.isFile()) {
            throw new GradleException("Layered test exclusion file does not exist: " + exclusionsFile);
        }
        try {
            return Files.readAllLines(exclusionsFile.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toSet());
        } catch (IOException exception) {
            throw new GradleException("Cannot read layered test exclusion file: " + exclusionsFile, exception);
        }
    }
}
