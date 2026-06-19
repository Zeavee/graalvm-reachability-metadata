/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.internal.tck.harness.tasks;

import java.util.List;

/**
 * Task that compiles native tests with the shared Native Image base layer.
 */
@SuppressWarnings("unused")
public abstract class LayeredNativeTestCompileInvocationTask extends NativeTestCompileInvocationTask {

    @Override
    public List<String> commandFor(String coordinates) {
        List<String> command = super.commandFor(coordinates);
        appendBaseLayerFileProperty(command);
        return command;
    }

    @Override
    protected String errorMessageFor(String coordinates, int exitCode) {
        return "Layered native test compilation failed";
    }
}
