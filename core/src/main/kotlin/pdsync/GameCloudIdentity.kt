package pdsync

/** One deterministic cloud family per actual game package, independent of platform and signing. */
object GameCloudIdentity {
 const val SHATTERED_PACKAGE = "com.shatteredpixel.shatteredpixeldungeon"

 @JvmStatic fun namespace(packageName: String): String {
  requireGamePackage(packageName)
  return if (packageName == SHATTERED_PACKAGE) "shattered"
  else "package-${hash("game-package-v1\n$packageName".encodeToByteArray())}"
 }
}
