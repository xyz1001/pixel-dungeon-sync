package pdsync

import java.nio.file.Path

/** Stable across launch directories. Explicit overrides are reserved for isolated runs. */
internal fun applicationWorkspace(overridePath: String? = System.getProperty("pdsync.workspace"), home: String = System.getProperty("user.home")): Path =
 (overridePath?.let(Path::of) ?: Path.of(home, ".pixel-dungeon-sync")).toAbsolutePath().normalize()
