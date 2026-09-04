# Sourcegen test suite TCK

The fixtures and tests every backend must satisfy. These sources are not a Gradle
project of their own: each backend suite adds them to its own source sets, so they
are annotation-processed and compiled once per backend and the tests run once per
backend.

Consumed by `test-suite-java` (source backend), `test-suite-bytecode` (ASM backend)
and `test-suite-bytecode-jdk` (JDK ClassFile backend).

A backend suite keeps its own sources for anything outside the shared contract, such
as fixtures that need a processor only that suite has on its class path.

When a test cannot hold for one backend, keep it here and disable it for that backend
with a reason rather than forking the file, so the gap stays visible:

```java
@DisabledIfSystemProperty(named = "sourcegen.backend", matches = "bytecode.*",
    disabledReason = "BeanIntrospection metadata is only produced by the source backend")
```

`sourcegen.backend` is set by each suite's build to `java`, `bytecode` or `bytecode-jdk`.
