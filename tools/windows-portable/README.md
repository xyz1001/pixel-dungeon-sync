# Windows x64 portable packaging

Builds a clean Windows portable ZIP on the current build host without running
Windows, downloading a JVM, or invoking native installer packaging. Product
classes are normal JVM 17 bytecode. The single host-specific Skiko runtime is
replaced with its **exact matching** official Windows x64 artifact.

Prerequisites: the project's configured JDK/Gradle, Python 3, resolved desktop
runtime dependencies, and the official Windows Skiko JAR. The packaging script
does not access the network. Obtain the pinned JAR over normally verified HTTPS:

- Coordinate: `org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.22.2`
- URL: <https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-windows-x64/0.9.22.2/skiko-awt-runtime-windows-x64-0.9.22.2.jar>
- SHA-256: `2d9ada9b3f4809d2d8aa53593336b92ec8c4c6cd14d6803c5bc260db96aac6c8`

After source changes are committed and the normal checks finish, run these from
the repository root using the configured Gradle, not a different user cache:

```sh
gradle --offline --no-daemon -I tools/windows-portable/export-runtime.gradle \
  :desktop:exportWindowsPortableRuntime \
  -PwindowsPortableClasspath=/absolute/output/runtime.json \
  -PwindowsPortableVersion=0.5.32-test -PwindowsPortableVersionCode=53
python3 tools/windows-portable/package.py \
  --runtime /absolute/output/runtime.json \
  --windows-runtime /absolute/cache/skiko-awt-runtime-windows-x64-0.9.22.2.jar \
  --version 0.5.32-test --version-code 53 \
  --output /absolute/output/PDSync-0.5.32-test-code53-windows-x64-portable.zip
```

The script requires a clean Git working tree and records the exact source commit,
runtime coordinates, archive hashes, required class-file versions, DLL SHA-256,
and PE x64 machine type. Both application JAR manifests receive and validate
`Implementation-Version`, `PDSync-Version-Code`, and `PDSync-Source-Revision`,
so package-level diagnostic metadata cannot fall back to a stale version.
It checks the bundled DLL against Skiko's own embedded
checksum, rejects non-Windows native libraries, and never copies whole source or
state directories. Only explicitly resolved runtime JARs and the included
launcher, instructions, and manifest enter the ZIP. There is no dependency
resolution or application startup in the Python packaging step.

The user unzips the whole folder and double-clicks `launcher.cmd` using an
already-installed Windows x64 Java 17+ (Java 21 recommended). The launcher keeps
the application's normal per-user workspace, so an existing configuration is
not relocated. This archive does not include a JRE, an installer, configuration,
saved credentials, game saves, logs, signing keys, or test fixtures.

An optional Java 17-compatible probe checks the extracted classpath and diagnostic
version with both possible application-package loading orders. It does not
initialize the application or load a native library:

```sh
java tools/windows-portable/VerifyPortableClasspath.java /extracted/package/lib 0.5.32-test
```

Archive and bytecode checks performed on Linux do **not** establish Windows GUI,
process detection, native secure storage, or live WebDAV compatibility. Report
the actual validation boundaries with each release.
