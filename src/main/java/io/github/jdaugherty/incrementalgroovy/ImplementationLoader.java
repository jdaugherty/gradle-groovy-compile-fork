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

import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.work.InputChanges;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Loads the compiler of this plugin, the classes below the {@code internal} package.
 * <p>
 * They are made from classes of Gradle and use the libraries that Gradle uses, Guava, ASM and fastutil, as part of the
 * signatures they share with Gradle. A plugin cannot see those libraries, and bringing its own would give it other classes
 * of the same names. So the compiler has a class loader of its own, whose parent is the one Gradle's Groovy compiler is
 * loaded by, and the classes of the plugin reach it through this class only.
 */
final class ImplementationLoader {
    private static final String EXECUTION_CLASS = "io.github.jdaugherty.incrementalgroovy.internal.GroovyCompileExecution";
    private static final String WORKER_CLASS = "io.github.jdaugherty.incrementalgroovy.internal.worker.DaemonSideCompiler";

    private static BiConsumer<GroovyCompile, InputChanges> groovyCompileExecution;

    private ImplementationLoader() {
    }

    /**
     * What a {@link GroovyCompile} task does when it runs.
     */
    @SuppressWarnings("unchecked")
    static synchronized BiConsumer<GroovyCompile, InputChanges> groovyCompileExecution() {
        if (groovyCompileExecution == null) {
            Set<URL> classpath = new LinkedHashSet<>();
            classpath.add(locationOf(EXECUTION_CLASS));
            classpath.add(locationOf(WORKER_CLASS));
            // Lives as long as the plugin's classes do
            ClassLoader loader = new URLClassLoader(classpath.toArray(new URL[0]), GroovyCompile.class.getClassLoader());
            try {
                groovyCompileExecution = (BiConsumer<GroovyCompile, InputChanges>) loader.loadClass(EXECUTION_CLASS).getConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new IllegalStateException("Cannot load the compiler of the incremental-groovy-compile plugin. This version of Gradle may not be supported.", e);
            }
        }
        return groovyCompileExecution;
    }

    /**
     * The jar or directory the class is in.
     */
    private static URL locationOf(String className) {
        String resourceName = className.replace('.', '/') + ".class";
        URL resource = ImplementationLoader.class.getClassLoader().getResource(resourceName);
        if (resource == null) {
            throw new IllegalStateException("Cannot find " + resourceName);
        }
        String url = resource.toExternalForm();
        String root = url.substring(0, url.length() - resourceName.length());
        if (root.startsWith("jar:") && root.endsWith("!/")) {
            root = root.substring("jar:".length(), root.length() - "!/".length());
        }
        try {
            return new URL(root);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Cannot locate " + className + " from " + url, e);
        }
    }
}
