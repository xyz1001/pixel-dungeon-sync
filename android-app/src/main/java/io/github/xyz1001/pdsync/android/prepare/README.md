# APK preparation trust boundaries

The fixed RSA identity in `PublicGameSigningIdentity` is intentionally public and is only a
compatibility key for generated game APKs. It was recovered from the verified published
code45 APK, not generated as a replacement. It is not the main application release key and
must never be used as evidence of the game publisher or of an APK's original source.

Input provenance is explicit:

- `VERIFIED_SIGNER`: apksig authenticated the selected bytes and their certificate set.
- `INSTALLED_SIGNER`: PackageManager plus a checked installed-source snapshot authenticated
  the certificate identity of a structurally unsigned installed extraction.
- `UNSIGNED`: the source has no original signer proof. Independent ZIP/signature-absence,
  manifest, CRC, DEX and complete split-set checks still apply. The stable unknown-origin
  namespace is derived from the Android package name, not an APK byte hash or output key.
- `INHERITED_EMBEDDED`: exact manifest continuity binding is retained from a recognized
  embedded runtime. Original signer certificates are explicitly unknown. The bound
  origin, namespace and historical source-set digest are preserved; the selected input
  set also has its own independent byte digest. This does not authenticate a publisher.

A local receipt is a derivative validation record. Exact matching retained outputs recover
and revalidate their original source set where possible. Without a receipt, runtime
replacement is limited to the current bundled DEX or the independently recovered code45
runtime SHA-256 `92aa07a4ebc64ccc9f78d3dae160afe191a342e7596c5e4b26cf56eb3699893d`.
An isolated final DEX, exact provider/activation/authority/namespace/version metadata,
no colliding bridge code anywhere in the full split set, and byte-for-byte preservation
of all non-runtime payload are required. Unknown historical runtime bytes fail closed;
package/class names and the public signing key do not establish ownership.

New runtime manifests advertise the pure-handshake protocol explicitly. Old manifests
without that marker remain parseable for constrained upgrades. Existing sandboxVersion
is preserved; signature validation is never bypassed by lowering it.

Output always receives exact payload/manifest checks and real apksig v2/v3 verification
at API26 and API36 with the expected fixed certificate. Source provenance does not relax
these checks. A process-memory, reference-bound validation cache only serves unchanged
app-private installation sessions; cold or changed files re-enter full validation, and
installer streaming retains byte-count/hash/fsync checks.

Host runners use binary manifests, real D8 bytes and real apksig, but stub Android framework
services and never install or execute an APK. They do not stand in for SAF/system-installer,
provider activation, PackageManager, OEM, or on-device Root tests.
