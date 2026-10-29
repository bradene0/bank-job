# ADR-0006: Java 25 LTS instead of Java 21

**Status:** Accepted (2026-10-29). Supersedes the original Java 21 choice.

## Context
The original stack specified Java 21 with the latest stable Spring Boot and a GraalVM native image
(ADR-0004). Spring Boot 4.1 requires GraalVM 25 or later for native images, and GraalVM 25
is built on JDK 25. Java 21, current Spring Boot and a native image cannot all hold at once.
Java 25 is the current LTS release (September 2025).

Options considered:
- Java 25 everywhere (chosen).
- Java 21 source level (`--release 21`) compiled by GraalVM 25: the docs stay technically
  true, but production would run on a JDK 25 runtime anyway.
- Spring Boot 3.5 on GraalVM 21: an older line close to the end of open-source support.

## Decision
Use Java 25 for game-api: Temurin 25 for the JVM build, test and CI, and GraalVM Community
25 for the native image.

## Consequences
- One Java version from laptop to production; no source/runtime mismatch to explain.
- Newer language features (e.g. flexible constructor bodies, module imports) are available.
- Some tools lag new JDKs. Seen so far: google-java-format needs `jdk.compiler` exports
  (`.mvn/jvm.config`), and 1.37.0 fails inside Spotless 3.10.4, so 1.35.0 is pinned.
