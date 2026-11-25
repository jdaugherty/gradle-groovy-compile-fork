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

import org.gradle.api.Task;
import org.gradle.api.internal.project.taskfactory.IncrementalTaskAction;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.internal.execution.history.changes.InputChangesInternal;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;

import java.lang.reflect.Method;

/**
 * Stands in for the action of a {@link GroovyCompile} task that calls its {@code compile(InputChanges)} method.
 * <p>
 * It is an {@link IncrementalTaskAction} because that is how Gradle tells that a task wants to know what changed.
 * A task that does not compile incrementally is left to Gradle.
 */
final class IncrementalGroovyCompileAction extends IncrementalTaskAction {
    private transient InputChangesInternal inputChanges;

    IncrementalGroovyCompileAction(Method compileMethod) {
        super(GroovyCompile.class, compileMethod);
    }

    @Override
    public void setInputChanges(InputChangesInternal inputChanges) {
        super.setInputChanges(inputChanges);
        this.inputChanges = inputChanges;
    }

    @Override
    public void clearInputChanges() {
        super.clearInputChanges();
        this.inputChanges = null;
    }

    @Override
    protected void doExecute(Task task, String methodName) {
        GroovyCompile groovyCompile = (GroovyCompile) task;
        if (groovyCompile.getOptions().isIncremental()) {
            ImplementationLoader.groovyCompileExecution().accept(groovyCompile, inputChanges);
        } else {
            super.doExecute(task, methodName);
        }
    }

    /**
     * The task has to run again when the plugin is added, removed or changed.
     */
    @Override
    public ImplementationSnapshot getActionImplementation(ClassLoaderHierarchyHasher hasher) {
        return ImplementationSnapshot.of(IncrementalGroovyCompileAction.class.getName(), hasher.getClassLoaderHash(IncrementalGroovyCompileAction.class.getClassLoader()));
    }
}
