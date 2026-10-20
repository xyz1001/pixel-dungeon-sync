# Code 63: direct APK-mode choice and quieter success UI

The Step 1 APK access card is the sole explicit start/retry action. The duplicate installation panel below the cards (including the Start, Retry and Reinstall variants) is removed. Android no longer publishes the duplicate CONTINUE_INSTALLATION capability. Older hosts supplying that row do not resurrect the panel in the shared view.

The card still dispatches the current GameModeChoice source through the existing ownership, foreground, busy, pending-request and recovery guards. A selected APK file remains the exact file/split set selected by the user. An installed source is inspected, checks its existing verified bridge and otherwise begins preparation directly. Recomposition and lifecycle return do not constitute a new mode choice.

The Root success notice is removed because the Root card already exposes the verified status. The redundant selected-APK notice is removed. Error notices, live progress, next-step gating, selection cancellation, system confirmation, external-install result checks, and cancel/end-wait recovery remain available. No installer routing, URI grants, signature verification or destructive-operation confirmations were relaxed.

The release retains the code61/code62 development signing identity. There is no automatic uninstall or data clearing. Ordinary single-APK installation retains the default ACTION_VIEW read-only content URI flow from code62.
