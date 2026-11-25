# Gradle Groovy Compile Fork

A fork of the incremental Groovy compiler of Gradle, packaged as a plugin that swaps it into Gradle's
`GroovyCompile` tasks. It carries the changes proposed to Gradle in
[gradle/gradle#39622](https://github.com/gradle/gradle/pull/39622), so that builds do not have to wait for a
Gradle release that has them.

This project is not affiliated with or endorsed by Gradle, Inc.

It is superseded by [incremental-groovy-compile](https://github.com/jdaugherty/incremental-groovy-compile), a plugin
with compile tasks of its own, and is kept for reference.

## What it fixes

With `options.incremental = true`, Gradle moves the class files of the classes it recompiles out of the output
directory for the time of the compilation. The Groovy compiler does not only read the classes of the previous
compilation, though, it loads some of them: the helper classes of precompiled traits, and whatever an AST
transformation asks for, as the GORM transformations of Grails do for domain classes. When such a class refers
to a class that is being recompiled, the compilation fails:

```
> Unrecoverable compilation error: startup failed:
  General error during canonicalization: java.lang.NoClassDefFoundError: Base
```

Gradle then falls back to a full recompilation, or fails the build
([#22519](https://github.com/gradle/gradle/issues/22519), [#22531](https://github.com/gradle/gradle/issues/22531),
[#22814](https://github.com/gradle/gradle/issues/22814)).

With the plugin:

- The JVM finds the classes being recompiled in their stashed class files, while the compiler still resolves
  them from source. Nothing more is recompiled for it.
- A recompiled class with a Java source has all of its dependents recompiled, as the Groovy compiler cannot
  read a class that refers to it until the Java compiler has run.
- The classes of the compile classpath are compared by their ABI. A dependency whose class files changed but
  whose ABI did not, no longer recompiles the classes that use it.

## Usage

The plugin is not published yet. Until it is, build it from source and include the build:

```groovy
// settings.gradle
pluginManagement {
    includeBuild('../gradle-groovy-compile-fork')
}
```

Apply it to every project that compiles Groovy incrementally:

```groovy
plugins {
    id 'groovy'
    id 'io.github.jdaugherty.incremental-groovy-compile'
}

tasks.withType(GroovyCompile).configureEach {
    options.incremental = true
}
```

Nothing else changes. The tasks remain Gradle's `GroovyCompile` tasks with all of their configuration, and a
task that does not have `options.incremental` on is compiled by Gradle as before.

Enabling the `GROOVY_COMPILATION_AVOIDANCE` feature preview is recommended. Without it, the compile classpath is
also the classpath of the AST transformations, and any change to it makes Gradle recompile everything.

### Supported versions

| Plugin | Gradle        | Compiler daemon JVM |
|--------|---------------|---------------------|
| 0.1    | 9.8.x, 9.9.x  | 8 and later         |

The plugin replaces internals of Gradle, so each version supports the Gradle versions it was tested with. On
any other version it logs a warning and leaves compilation to Gradle. The Gradle property
`incrementalGroovyCompile.enabled` overrides that: `true` to try the plugin on an untested version, `false` to
turn it off.

The configuration cache is supported.

## How it works

Gradle has no extension point for its incremental compiler, so the plugin brings its own copy:

- The classes of Gradle's incremental compiler that the fix touches are copied into
  `io.github.jdaugherty.incrementalgroovy.internal`, with the changes of the pull request applied. Each names the
  Gradle source file it comes from. The first commits of this repository are the unchanged import and the
  changes, in that order.
- The plugin replaces the action of each `GroovyCompile` task with one that runs this compiler. Gradle still
  creates the compile spec, the worker daemon, and runs its own Java and Groovy compiler classes for everything
  that is not changed.
- These classes share Guava, ASM and fastutil types with Gradle, which plugins cannot see. They are loaded by a
  class loader whose parent is the one of Gradle's Groovy compiler, see `ImplementationLoader`.
- In the compiler daemon, the plugin's compiler classes are added to the class loader of Gradle's.

Gradle still decides whether a compile task has to start, from its own reading of the compile classpath. When
Gradle takes a dependency as changed and the plugin's comparison does not, the task starts, recompiles nothing,
and is reported as `UP-TO-DATE`.

## Building

```
./gradlew build
```

The functional tests run builds with each supported Gradle version, with and without the plugin.

### Moving to a newer Gradle version

The `importUpstream` task copies the forked classes from a Gradle checkout. To see what a new Gradle version
changed in them, import both versions into scratch directories and compare:

```
./gradlew importUpstream -PgradleCheckout=~/gradle -PgradleRef=v9.9.2 -PimportInto=/tmp/old
./gradlew importUpstream -PgradleCheckout=~/gradle -PgradleRef=v9.10.0 -PimportInto=/tmp/new
diff -ru /tmp/old /tmp/new
```

Apply the differences, raise the supported versions in `IncrementalGroovyCompilePlugin` and in
`build.gradle`, and run the tests.

## License

[Apache License 2.0](LICENSE), the license of Gradle.

The classes below `io.github.jdaugherty.incrementalgroovy.internal` that are derived from Gradle keep Gradle's
license header and copyright, followed by a note naming the Gradle source file they come from and stating that
they were changed. [NOTICE](NOTICE) carries the attribution. Both files are in the jar under `META-INF`.

`./gradlew check` runs [Apache RAT](https://creadur.apache.org/rat/), which fails the build when a file has no
license header.
