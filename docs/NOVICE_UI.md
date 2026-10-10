# Novice UI revision

The three setup pages now emphasize the current result and next action. Game paths,
file counts and save format details are collapsed by default. Discovery distinguishes
an existing directory from verified save contents, and unknown comparison results
never claim emptiness or synchronization. Home records show device and relevant time.

Restore confirmation still explicitly warns that it replaces local saves and removes
progress absent from the selected snapshot. Connection failures keep short actionable
feedback with technical information available under details.

Validation: offline desktop tests on Windows, JDK 21.0.12.1+1, Gradle 8.14.3.
Tests cover discovery wording, unknown/blocked comparison, credential and compatibility
errors, collapsed detail state, and the replacement warning. Native UI clicking and
visual layout inspection were unavailable in this environment; this is not a claim
of interactive end-to-end coverage. Sync protocol and compatibility policy unchanged.

The verified both-empty setup state now displays only `配置完成`, with `上一步`
and `完成` below it. No local/cloud cards, details, or parallel actions appear in
that state. Finish retains fresh comparison and readiness checks before saving
the existing initialization decision; it does not upload, restore, or invent a
baseline. Leaving the page or an in-flight finish blocks repeated completion.
Unknown, blocked, identical and differing states cannot enter this empty-state
shortcut. Nonempty flows and home upload/restore actions are unchanged.
