# Code 64: remove redundant installer footer UI

Removes the other-installer selection row from the Android Step 1 capabilities and suppresses stale host rows in the shared view. Removes the installation-request footer heading. Once APK mode is verified ready, its redundant success notice is no longer published; the existing card ready badge and next button remain unchanged. Error, pending request, cancellation and recovery behavior is unchanged. No replacement controls, explanatory text or installer chooser are added.

The ordinary single-APK default ACTION_VIEW content URI flow, duplicate-request protections and cancellation logic are unchanged. Genuine pending requests retain their existing confirmation, check and cancel actions. The HTML references contain none of the removed footer text, so no unrelated HTML changes are made.

Version: 0.5.43-test / 64. Retains the code61–63 signing certificate. Cloud regression and build checks do not claim installation on the user's physical device.
