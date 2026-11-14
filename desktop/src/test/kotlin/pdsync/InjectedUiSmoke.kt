package pdsync

/** Not part of desktop.jar. Explicit test launcher for UI flows when host process visibility is blocked. */
object InjectedUiSmoke {
 @JvmStatic fun main(args: Array<String>) {
  check(System.getenv("PDSYNC_TEST_INJECTED_GUARD") == "acknowledged-test-fixture-only")
  check(System.getProperty("pdsync.workspace").contains("pds-automatic-v8"))
  println("TEST-ONLY UI: process guard injected Stopped; production detector is NOT being validated")
  runSyncApplication(title = "v8 UI TEST")
 }
}
