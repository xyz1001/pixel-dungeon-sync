// Host-only isolated install-result fixture. Never package into an APK.
package pdsync;
public final class DiagnosticDetails {
 public static Builder builder() { return new Builder(); }
 public static final class Builder {
  public Builder failureSource(String v) { return this; } public Builder installationPhase(String v) { return this; }
  public Builder installationCache(String v) { return this; } public Builder installationElapsedMs(long v) { return this; }
  public DiagnosticDetails build() { return new DiagnosticDetails(); }
 }
}
