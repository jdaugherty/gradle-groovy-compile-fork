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
package io.github.jdaugherty.incrementalgroovy.internal;

import io.github.jdaugherty.incrementalgroovy.internal.daemon.DaemonGroovyCompiler;
import io.github.jdaugherty.incrementalgroovy.internal.incremental.IncrementalCompilerFactory;
import io.github.jdaugherty.incrementalgroovy.internal.incremental.classpath.DefaultClassSetAnalyzer;
import io.github.jdaugherty.incrementalgroovy.internal.incremental.recomp.GroovyRecompilationSpecProvider;
import io.github.jdaugherty.incrementalgroovy.internal.worker.DaemonSideCompiler;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.AbstractTask;
import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.tasks.compile.AnnotationProcessorDiscoveringCompiler;
import org.gradle.api.internal.tasks.compile.CleaningJavaCompiler;
import org.gradle.api.internal.tasks.compile.GroovyJavaJointCompileSpec;
import org.gradle.api.internal.tasks.compile.NormalizingGroovyCompiler;
import org.gradle.api.internal.tasks.compile.daemon.ClassloaderIsolatedCompilerWorkerExecutor;
import org.gradle.api.internal.tasks.compile.daemon.CompilerWorkerExecutor;
import org.gradle.api.internal.tasks.compile.daemon.ProcessIsolatedCompilerWorkerExecutor;
import org.gradle.api.internal.tasks.compile.incremental.analyzer.ClassDependenciesAnalyzer;
import org.gradle.api.internal.tasks.compile.incremental.cache.GeneralCompileCaches;
import org.gradle.api.internal.tasks.compile.incremental.classpath.CachingClassSetAnalyzer;
import org.gradle.api.internal.tasks.compile.incremental.classpath.ClassSetAnalyzer;
import org.gradle.api.internal.tasks.compile.processing.AnnotationProcessorDetector;
import org.gradle.api.problems.internal.ProblemsInternal;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.initialization.layout.ProjectCacheDir;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.hash.FileHasher;
import org.gradle.internal.hash.StreamHasher;
import org.gradle.internal.jvm.inspection.JvmVersionDetector;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.process.internal.worker.child.WorkerDirectoryProvider;
import org.gradle.work.InputChanges;
import org.gradle.workers.internal.ActionExecutionSpecFactory;
import org.gradle.workers.internal.IsolatedClassloaderWorkerFactory;
import org.gradle.workers.internal.WorkerDaemonFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.function.BiConsumer;

/**
 * Does what {@code GroovyCompile#compile(InputChanges)} does, with this project's incremental compiler and compiler
 * daemon in place of Gradle's. The task itself, its properties and the compile spec it makes are Gradle's.
 * <p>
 * Loaded by name by the plugin, see {@code ImplementationLoader}.
 */
public final class GroovyCompileExecution implements BiConsumer<GroovyCompile, InputChanges> {
    /**
     * Classpath entries are compared by ABI here and by content in Gradle, so their analyses are cached apart.
     */
    private static final String CLASSPATH_ANALYSIS_SALT = "io.github.jdaugherty.incrementalgroovy:abi:1";

    @Override
    public void accept(GroovyCompile task, InputChanges inputChanges) {
        invoke(GroovyCompile.class, task, "checkGroovyClasspathIsNonEmpty");
        invoke(GroovyCompile.class, task, "warnIfCompileAvoidanceEnabled");
        GroovyJavaJointCompileSpec spec = (GroovyJavaJointCompileSpec) invoke(GroovyCompile.class, task, "createSpec");
        invoke(GroovyCompile.class, task, "maybeDisableIncrementalCompilationAfterFailure", new Class<?>[]{GroovyJavaJointCompileSpec.class}, spec);
        WorkResult result = createCompiler(task, spec, inputChanges).execute(spec);
        task.setDidWork(result.getDidWork());
    }

    private static Compiler<GroovyJavaJointCompileSpec> createCompiler(GroovyCompile task, GroovyJavaJointCompileSpec spec, InputChanges inputChanges) {
        ServiceRegistry services = (ServiceRegistry) invoke(AbstractTask.class, task, "getServices");
        Deleter deleter = services.get(Deleter.class);
        StashedClassFiles stashedClassFiles = new StashedClassFiles();

        // As GroovyCompilerFactory does
        ActionExecutionSpecFactory actionExecutionSpecFactory = services.get(ActionExecutionSpecFactory.class);
        ProjectCacheDir projectCacheDir = services.get(ProjectCacheDir.class);
        CompilerWorkerExecutor compilerWorkerExecutor = spec.getGroovyCompileOptions().isFork()
            ? new ProcessIsolatedCompilerWorkerExecutor(services.get(WorkerDaemonFactory.class), actionExecutionSpecFactory, projectCacheDir)
            : new ClassloaderIsolatedCompilerWorkerExecutor(services.get(IsolatedClassloaderWorkerFactory.class), actionExecutionSpecFactory, projectCacheDir);
        Compiler<GroovyJavaJointCompileSpec> groovyCompiler = new DaemonGroovyCompiler(
            services.get(WorkerDirectoryProvider.class).getWorkingDirectory(),
            services.get(ClassPathRegistry.class),
            compilerWorkerExecutor,
            services.get(ClassLoaderRegistry.class),
            services.get(JavaForkOptionsFactory.class),
            services.get(JvmVersionDetector.class),
            services.get(ProblemsInternal.class),
            stashedClassFiles,
            workerClasses()
        );
        Compiler<GroovyJavaJointCompileSpec> delegatingCompiler = new AnnotationProcessorDiscoveringCompiler<>(new NormalizingGroovyCompiler(groovyCompiler), services.get(AnnotationProcessorDetector.class));

        // As GroovyCompile does
        CleaningJavaCompiler<GroovyJavaJointCompileSpec> cleaningGroovyCompiler = new CleaningJavaCompiler<>(delegatingCompiler, task.getOutputs(), deleter);
        if (!spec.incrementalCompilationEnabled()) {
            return cleaningGroovyCompiler;
        }
        FileCollection stableSources = (FileCollection) invoke(GroovyCompile.class, task, "getStableSources");
        return incrementalCompilerFactory(services).makeIncremental(
            cleaningGroovyCompiler,
            stableSources.getAsFileTree(),
            new GroovyRecompilationSpecProvider(
                deleter,
                services.get(FileOperations.class),
                stableSources.getAsFileTree(),
                inputChanges.isIncremental(),
                () -> inputChanges.getFileChanges(stableSources).iterator(),
                stashedClassFiles
            )
        );
    }

    /**
     * As Gradle's {@code CompileServices} do, with Gradle's analysis of single classes and its caches.
     */
    private static IncrementalCompilerFactory incrementalCompilerFactory(ServiceRegistry services) {
        ClassSetAnalyzer classSetAnalyzer = new CachingClassSetAnalyzer(
            new DefaultClassSetAnalyzer(services.get(FileHasher.class), services.get(StreamHasher.class), services.get(ClassDependenciesAnalyzer.class), services.get(FileOperations.class)),
            services.get(FileSystemAccess.class),
            new SaltedCache<>(services.get(GeneralCompileCaches.class).getClassSetAnalysisCache(), CLASSPATH_ANALYSIS_SALT)
        );
        return new IncrementalCompilerFactory(services.get(BuildOperationExecutor.class), services.get(StringInterner.class), classSetAnalyzer);
    }

    /**
     * The jar or directory that holds the classes of this project that run in the compiler daemon.
     */
    private static URL workerClasses() {
        String resourceName = DaemonSideCompiler.class.getName().replace('.', '/') + ".class";
        URL resource = GroovyCompileExecution.class.getClassLoader().getResource(resourceName);
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
            throw new IllegalStateException("Cannot locate the compiler classes from " + url, e);
        }
    }

    private static Object invoke(Class<?> declaringClass, Object target, String methodName) {
        return invoke(declaringClass, target, methodName, new Class<?>[0]);
    }

    /**
     * Calls a method that Gradle keeps to the task.
     */
    private static Object invoke(Class<?> declaringClass, Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = declaringClass.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This version of Gradle is not supported: " + declaringClass.getSimpleName() + "." + methodName + " is not what it was", e);
        }
    }
}
