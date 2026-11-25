/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jdaugherty.incrementalgroovy;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.project.taskfactory.IncrementalTaskAction;
import org.gradle.api.internal.tasks.InputChangesAwareTaskAction;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.util.GradleVersion;
import org.gradle.work.InputChanges;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Makes the {@link GroovyCompile} tasks of the project compile incrementally with this plugin's compiler.
 * <p>
 * The tasks stay what they are. Only what they do when they run with {@code options.incremental} on is replaced, see
 * {@link IncrementalGroovyCompileAction}.
 */
public class IncrementalGroovyCompilePlugin implements Plugin<Project> {
    /**
     * The Gradle property that turns the plugin off when {@code false}, and on for a version of Gradle it was not made for
     * when {@code true}.
     */
    public static final String ENABLED_PROPERTY = "incrementalGroovyCompile.enabled";

    // The plugin replaces internals of Gradle, which are the same in these versions
    private static final GradleVersion FIRST_SUPPORTED = GradleVersion.version("9.8");
    private static final GradleVersion FIRST_UNSUPPORTED = GradleVersion.version("9.10");

    private static final Logger LOGGER = Logging.getLogger(IncrementalGroovyCompilePlugin.class);
    private static final String COMPILE_METHOD = "compile";

    @Override
    public void apply(Project project) {
        String enabled = project.getProviders().gradleProperty(ENABLED_PROPERTY).getOrNull();
        if ("false".equalsIgnoreCase(enabled)) {
            return;
        }
        GradleVersion gradleVersion = GradleVersion.current().getBaseVersion();
        boolean supported = gradleVersion.compareTo(FIRST_SUPPORTED) >= 0 && gradleVersion.compareTo(FIRST_UNSUPPORTED) < 0;
        if (!supported && !"true".equalsIgnoreCase(enabled)) {
            LOGGER.warn(
                "The incremental-groovy-compile plugin does not support {}, only Gradle {} and later, before {}. Groovy is compiled by Gradle alone. Set the Gradle property {}=true to try the plugin anyway.",
                GradleVersion.current(), FIRST_SUPPORTED.getVersion(), FIRST_UNSUPPORTED.getVersion(), ENABLED_PROPERTY
            );
            return;
        }
        Method compileMethod;
        try {
            compileMethod = GroovyCompile.class.getDeclaredMethod(COMPILE_METHOD, InputChanges.class);
        } catch (NoSuchMethodException e) {
            LOGGER.warn("The incremental-groovy-compile plugin does not support {}: its GroovyCompile task is not what it was. Groovy is compiled by Gradle alone.", GradleVersion.current());
            return;
        }
        project.getTasks().withType(GroovyCompile.class).configureEach(task -> replaceCompileAction(task, compileMethod));
    }

    private static void replaceCompileAction(GroovyCompile task, Method compileMethod) {
        List<InputChangesAwareTaskAction> actions = ((TaskInternal) task).getTaskActions();
        for (int i = 0; i < actions.size(); i++) {
            InputChangesAwareTaskAction action = actions.get(i);
            if (action.getClass() == IncrementalTaskAction.class && action.getDisplayName().equals("Execute " + COMPILE_METHOD)) {
                actions.set(i, new IncrementalGroovyCompileAction(compileMethod));
                return;
            }
        }
        LOGGER.warn("The incremental-groovy-compile plugin did not find the compile action of {}. Gradle alone compiles it.", task);
    }
}
