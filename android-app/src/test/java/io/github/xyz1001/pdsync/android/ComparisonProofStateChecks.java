package io.github.xyz1001.pdsync.android;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only tests of the production model; these do not simulate UI or core operations. */
public final class ComparisonProofStateChecks {
    private static int checks;
    private static final Object FACADE = new EqualObject();
    private static final Object CONNECTION = new EqualObject();
    private static final Object INSPECTION = new EqualObject();
    private static final ComparisonProofState.Context GAME = context();

    /** Value equality must never stand in for the identity of a core observation. */
    private static final class EqualObject {
        @Override public boolean equals(Object other) { return other instanceof EqualObject; }
        @Override public int hashCode() { return 1; }
    }

    private static ComparisonProofState.Context context() {
        return new ComparisonProofState.Context(FACADE, CONNECTION, INSPECTION, "root:game-a");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static ComparisonProofState state() {
        ComparisonProofState value = new ComparisonProofState();
        value.bind(GAME);
        return value;
    }

    private static boolean current(ComparisonProofState state, ComparisonProofState.Ticket ticket) {
        return state.current(ticket.token, GAME, true, false, ticket.stage);
    }

    private static ComparisonProofState.Ticket claim(ComparisonProofState state,
            ComparisonProofState.Ticket ticket) {
        return state.claim(ticket.token, GAME, true, false, ticket.stage);
    }

    private static void empty(ComparisonProofState state, String reason) {
        check(state.progress() == null, reason + " clears explicit progress");
        check(state.selectedId() == null, reason + " clears remote selection");
        check(state.dialog() == null, reason + " clears visible modal");
    }

    private static void contextIdentity() {
        ComparisonProofState.Context same = new ComparisonProofState.Context(
                FACADE, CONNECTION, INSPECTION, new String("root:game-a"));
        check(GAME.equals(same) && same.equals(GAME), "Context compares object identity and backend value");
        check(GAME.hashCode() == same.hashCode(), "Equal contexts have equal hashes");
        check(!GAME.equals(null) && !GAME.equals("root:game-a"), "Context excludes null and other types");
        check(!GAME.equals(new ComparisonProofState.Context(new EqualObject(), CONNECTION,
                INSPECTION, "root:game-a")), "Facade equality cannot substitute for identity");
        check(!GAME.equals(new ComparisonProofState.Context(FACADE, new EqualObject(),
                INSPECTION, "root:game-a")), "Connection equality cannot substitute for identity");
        check(!GAME.equals(new ComparisonProofState.Context(FACADE, CONNECTION,
                new EqualObject(), "root:game-a")), "Inspection equality cannot substitute for identity");
        check(!GAME.equals(new ComparisonProofState.Context(FACADE, CONNECTION,
                INSPECTION, "embedded:game-a")), "Backend change changes proof identity");
    }

    private static void explicitChoiceAndRerender() {
        ComparisonProofState state = new ComparisonProofState();
        empty(state, "Unbound entry");
        check(!state.matches(null), "An absent context is not a proof");
        check(!state.bind(null), "An unbound rerender is a no-op");
        check(state.openConflict() == null, "An unbound state cannot show a proof modal");
        check(state.capture(null, true, false) == null, "An unbound state cannot capture Finish");
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "single");
        empty(state, "An unbound choice");
        check(state.bind(GAME), "The first real observation binds");
        empty(state, "Initial observation");
        check(state.matches(context()), "Equivalent current context matches");
        ComparisonProofState.Ticket first = state.openConflict();
        check(first != null && first.snapshotId == null, "Opening a modal never auto-selects a head");
        check(state.progress() == null, "Showing comparison never creates a progress choice");
        check(!state.bind(context()), "Ordinary rerender does not bind a new observation");
        check(state.dialog() == first && current(state, first), "Rerender preserves token and dialog identity");
        state.selectProgress(ComparisonProofState.Progress.LOCAL, "single");
        check(state.progress() == ComparisonProofState.Progress.LOCAL, "Local choice is explicit");
        check(state.selectedId() == null, "Local choice never binds a supplied single cloud head");
        check(state.dialog() == null && !current(state, first), "Changing progress revokes an old modal");
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "single");
        check(state.progress() == ComparisonProofState.Progress.CLOUD, "Cloud choice is explicit");
        check("single".equals(state.selectedId()), "Only explicit cloud choice binds its single head");
        first = state.openConflict();
        check("single".equals(first.snapshotId), "Conflict ticket captures the chosen snapshot");
        check(!state.bind(context()) && state.dialog() == first, "A rerender preserves a selected snapshot");
        check(state.progress() == ComparisonProofState.Progress.CLOUD, "Rerender preserves explicit choice");
        state.selectProgress(ComparisonProofState.Progress.CLOUD, null);
        check(state.selectedId() == null, "Multiple cloud heads start without an implicit winner");
        check(!current(state, first), "Even a repeated explicit choice invalidates the prior token");
        check(state.selectRemote("head-a") == null, "Remote selection requires a conflict modal");
        check(state.selectedId() == null, "Rejected remote selection does not mutate state");
        first = state.openConflict();
        ComparisonProofState.Ticket chosen = state.selectRemote("head-a");
        check(chosen != null && "head-a".equals(chosen.snapshotId), "User can select a multi-head candidate");
        check(chosen.token > first.token && !current(state, first), "Selection gives a fresh modal token");
        ComparisonProofState.Ticket second = state.selectRemote("head-b");
        check(second.token > chosen.token && "head-b".equals(state.selectedId()), "A second selection refreshes proof");
        check(!current(state, chosen) && current(state, second), "The earlier candidate cannot be claimed");
        check(claim(state, chosen) == null, "A stale selection cannot transfer proof to a worker");
        check(state.dialog() == second, "A stale callback cannot disturb the replacement selection");
        ComparisonProofState.Ticket reopened = state.openConflict();
        check(reopened.token > second.token && !current(state, second), "Reopening replaces the modal token");
        check("head-b".equals(reopened.snapshotId), "Reopening retains the explicit remote choice");
    }

    private static void cancellationAndLifecycle() {
        ComparisonProofState state = state();
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "single");
        ComparisonProofState.Ticket first = state.openConflict();
        check(state.cancel(first.token, GAME, true, false), "Current modal can be cancelled");
        check(state.dialog() == null && !current(state, first), "Cancel invalidates the modal token");
        check(state.progress() == ComparisonProofState.Progress.CLOUD
                && "single".equals(state.selectedId()), "Cancel retains explicit progress and selection");
        check(state.matches(GAME), "Cancel retains the observation");
        check(!state.cancel(first.token, GAME, true, false), "Repeated cancellation is harmless");
        check(claim(state, first) == null, "A dismissed dialog can never be claimed");
        ComparisonProofState.Ticket newer = state.openConflict();
        check(newer.token > first.token, "Cancelled token cannot be recycled");
        check(state.review(first, GAME, true, false, true) == null, "Delayed preview cannot resurrect cancellation");
        check(state.dialog() == newer, "Delayed cancelled preview preserves the new dialog");

        for (String cause : new String[]{"pause", "leave", "Back", "destroy", "rotation", "game reset"}) {
            state = state();
            state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
            first = state.openConflict();
            state.reset();
            empty(state, cause);
            check(!state.matches(GAME), cause + " clears the observation");
            check(!current(state, first) && claim(state, first) == null, cause + " rejects stale callbacks");
            check(state.review(first, GAME, true, false, true) == null, cause + " rejects delayed merge review");
            check(state.bind(GAME), cause + " requires a fresh binding even for the same object identity");
            newer = state.openConflict();
            check(newer.token > first.token, cause + " cannot recycle an earlier epoch");
            check(!current(state, first), cause + " rejects old proof after foreground reentry");
            check(state.dialog() == newer, cause + " preserves the replacement dialog");

            state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
            ComparisonProofState.Ticket claimed = state.capture(GAME, true, false);
            check(state.claimedCurrent(claimed, GAME, true), cause + " has an initial claimed proof");
            state.reset();
            empty(state, cause + " with claimed work");
            check(!state.claimedCurrent(claimed, GAME, true), cause + " revokes claimed and queued work");
            state.bind(GAME);
            check(!state.claimedCurrent(claimed, GAME, true), cause + " cannot revive a claim on reentry");
        }
    }

    private static void changedObservations() {
        ComparisonProofState.Context[] changes = {
                new ComparisonProofState.Context(new EqualObject(), CONNECTION, INSPECTION, "root:game-a"),
                new ComparisonProofState.Context(FACADE, new EqualObject(), INSPECTION, "root:game-a"),
                new ComparisonProofState.Context(FACADE, CONNECTION, new EqualObject(), "root:game-a"),
                new ComparisonProofState.Context(FACADE, CONNECTION, INSPECTION, "root:game-b"),
                new ComparisonProofState.Context(FACADE, CONNECTION, INSPECTION, "embedded:game-a"),
                null
        };
        String[] causes = {"facade", "connection/account", "new inspection", "selected game", "backend", "missing context"};
        for (int i = 0; i < changes.length; i++) {
            ComparisonProofState.Context changed = changes[i];
            String cause = causes[i];
            ComparisonProofState state = state();
            state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
            ComparisonProofState.Ticket first = state.openConflict();
            check(!state.current(first.token, changed, true, false, first.stage), cause + " rejects mismatched callback context");
            check(state.review(first, changed, true, false, true) == null, cause + " cannot approve an old preview");
            check(state.dialog() == first && current(state, first), cause + " rejected callback cannot mutate current proof");
            check(state.bind(changed), cause + " is a new observation");
            empty(state, cause);
            check(!current(state, first), cause + " invalidates the previous token");
            check(state.review(first, GAME, true, false, true) == null, cause + " cannot resurrect prior review");
            state.bind(GAME);
            check(!current(state, first), cause + " cannot revive proof by switching back");
            state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
            ComparisonProofState.Ticket claimed = state.capture(GAME, true, false);
            check(state.claimedCurrent(claimed, context(), true), cause + " starts with a valid claim");
            check(!state.claimedCurrent(claimed, changed, true), cause + " denies worker with mismatched context");
            check(state.bind(changed), cause + " invalidates claimed proof too");
            empty(state, cause + " during claimed work");
            check(!state.claimedCurrent(claimed, GAME, true), cause + " revokes the worker claim");
        }
    }

    private static void mergeReview() {
        ComparisonProofState state = state();
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "head-a");
        ComparisonProofState.Ticket original = state.openConflict();
        check(state.review(null, GAME, true, false, true) == null, "Missing preview ticket is rejected");
        ComparisonProofState.Ticket reviewed = state.review(original, GAME, true, false, true);
        check(reviewed != null && reviewed.stage == ComparisonProofState.Stage.MERGE_REVIEW,
                "An allowed current preview creates review proof");
        check(reviewed.token > original.token && reviewed != original, "Merge review requires a fresh ticket");
        check("head-a".equals(reviewed.snapshotId) && reviewed.context.equals(GAME), "Review retains captured observation and selection");
        check(!current(state, original) && current(state, reviewed), "Conflict confirmation cannot bypass merge review");
        check(state.claim(reviewed.token, GAME, true, false, ComparisonProofState.Stage.CONFLICT) == null,
                "Review ticket cannot be claimed through the conflict action");
        check(state.selectRemote("head-b") == null && "head-a".equals(state.selectedId()),
                "Remote selection is not allowed in merge review");
        check(state.review(original, GAME, true, false, true) == null, "Duplicate preview cannot replace review proof");
        check(state.review(reviewed, GAME, true, false, true) == null, "Review cannot recursively authorize another review");
        check(state.dialog() == reviewed, "Rejected callbacks leave the review intact");
        check(state.cancel(reviewed.token, GAME, true, false), "Merge review supports cancellation");
        check(state.review(original, GAME, true, false, true) == null && state.dialog() == null,
                "Delayed preview cannot reopen a cancelled review");

        original = state.openConflict();
        check(state.review(original, GAME, true, false, false) == null, "Denied preview does not return merge proof");
        ComparisonProofState.Ticket denied = state.dialog();
        check(denied != null && denied.stage == ComparisonProofState.Stage.CONFLICT, "Denied preview reissues a conflict modal");
        check(denied.token > original.token && !current(state, original), "Denied preview revokes the original token");
        check("head-a".equals(denied.snapshotId) && state.progress() == ComparisonProofState.Progress.CLOUD,
                "Denied preview preserves explicit choice");
        check(state.review(original, GAME, true, false, true) == null && state.dialog() == denied,
                "A late allowed result cannot override a denied preview");
        ComparisonProofState.Ticket chosen = state.selectRemote("head-b");
        check(state.review(denied, GAME, true, false, true) == null && state.dialog() == chosen,
                "A delayed preview cannot replace a newer remote choice");
        state.selectProgress(ComparisonProofState.Progress.LOCAL, "head-b");
        check(state.review(chosen, GAME, true, false, true) == null && state.dialog() == null,
                "A delayed preview cannot revive a modal after a new progress choice");
    }

    private static void claimAndFinish() {
        ComparisonProofState state = state();
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "head-a");
        ComparisonProofState.Ticket original = state.openConflict();
        check(state.capture(GAME, true, false) == null, "Nonmodal Finish cannot bypass a visible modal");
        ComparisonProofState.Ticket claimed = claim(state, original);
        check(claimed == original && state.dialog() == null, "Claim transfers exactly the visible proof and closes the modal");
        check(!current(state, original), "Claimed proof cannot be reused by another dialog callback");
        check(claim(state, original) == null, "A token cannot be claimed twice");
        check(state.capture(GAME, true, false) == null, "Finish cannot issue another claim while work is claimed");
        check(!state.cancel(original.token, GAME, true, false), "A late dismiss callback cannot cancel transferred proof");
        check(state.review(original, GAME, true, false, true) == null, "Delayed preview cannot replace a claim");
        check(state.claimedCurrent(claimed, GAME, true), "Claim remains valid for worker revalidation");
        check(state.claimedCurrent(claimed, context(), true), "Rereading the same observation preserves claimed proof");
        check(!state.bind(context()) && state.claimedCurrent(claimed, GAME, true), "Rerender preserves the claimed epoch");
        check(!state.claimedCurrent(null, GAME, true), "Null ticket is never a worker proof");
        check(!state.claimedCurrent(claimed, GAME, false), "Background workers cannot consume foreground proof");
        check(state.progress() == ComparisonProofState.Progress.CLOUD && "head-a".equals(state.selectedId()),
                "Claim retains the explicit choice for the worker");
        state.selectProgress(ComparisonProofState.Progress.LOCAL, "head-a");
        check(!state.claimedCurrent(claimed, GAME, true), "A new explicit progress choice revokes claimed work");
        ComparisonProofState.Ticket finish = state.capture(GAME, true, false);
        check(finish != null && finish.token > claimed.token, "Explicit nonmodal Finish creates fresh claimed proof");
        check(finish.snapshotId == null && state.progress() == ComparisonProofState.Progress.LOCAL,
                "Finish preserves local choice without cloud selection");
        check(state.dialog() == null && state.claimedCurrent(finish, GAME, true), "Nonmodal Finish is immediately worker-owned");
        check(state.capture(GAME, true, false) == null, "Repeated Finish cannot create duplicate claimed work");
        original = state.openConflict();
        check(!state.claimedCurrent(finish, GAME, true), "A new explicit conflict flow revokes an earlier claim");
        check(current(state, original), "Replacement conflict proof is current");
        state.reset();
        state.bind(GAME);
        finish = state.capture(GAME, true, false);
        check(finish != null && state.progress() == null && state.selectedId() == null,
                "Finish captures proof without inventing a choice where none is needed");
        check(state.claimedCurrent(finish, GAME, true), "Choice-free Finish still has current observation proof");
    }

    private static void foregroundAndBusy() {
        for (boolean foreground : new boolean[]{false, true}) {
            for (boolean busy : new boolean[]{false, true}) {
                if (foreground && !busy) continue;
                String cause = "foreground=" + foreground + ", busy=" + busy;
                ComparisonProofState state = state();
                state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
                ComparisonProofState.Ticket original = state.openConflict();
                check(!state.current(original.token, GAME, foreground, busy, original.stage), cause + " blocks modal actions");
                check(state.claim(original.token, GAME, foreground, busy, original.stage) == null, cause + " cannot claim");
                check(!state.cancel(original.token, GAME, foreground, busy), cause + " cannot cancel current proof");
                check(state.review(original, GAME, foreground, busy, true) == null, cause + " cannot accept a preview");
                check(state.review(original, GAME, foreground, busy, false) == null, cause + " cannot apply a denied preview");
                check(state.dialog() == original && current(state, original), cause + " rejected callbacks do not mutate proof");
                check(state.cancel(original.token, GAME, true, false), cause + " still permits a current foreground cancellation");
                check(state.capture(GAME, foreground, busy) == null, cause + " cannot capture Finish");
                ComparisonProofState.Ticket claimed = state.capture(GAME, true, false);
                check(state.claimedCurrent(claimed, GAME, true), cause + " hard busy does not invalidate the owning worker");
                check(!state.claimedCurrent(claimed, GAME, false), cause + " worker still requires foreground");
            }
        }
        ComparisonProofState state = state();
        ComparisonProofState.Ticket original = state.openConflict();
        check(!state.current(original.token + 1, GAME, true, false, original.stage), "Wrong epoch is not current");
        check(!state.current(original.token, GAME, true, false, ComparisonProofState.Stage.MERGE_REVIEW), "Wrong stage is not current");
        check(!state.current(original.token, null, true, false, original.stage), "Missing context is not current");
        check(!state.cancel(original.token + 1, GAME, true, false), "A stale dismissal cannot close a newer modal");
        check(state.dialog() == original, "Invalid proof checks leave the visible modal intact");
    }

    private static void releaseAndRetry() {
        ComparisonProofState state = state();
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
        ComparisonProofState.Ticket first = state.openConflict();
        check(!state.releaseClaim(first), "An unclaimed modal cannot be released as worker proof");
        check(state.dialog() == first && current(state, first), "Invalid release preserves the visible modal");
        ComparisonProofState.Ticket claimed = claim(state, first);
        check(!state.releaseClaim(null), "Null cannot release claimed proof");
        check(state.releaseClaim(claimed), "Current worker can release its claim after routine completion");
        check(!state.claimedCurrent(claimed, GAME, true), "Release invalidates the old worker token");
        check(state.dialog() == null && state.matches(GAME), "Release preserves observation without opening a modal");
        check(state.progress() == ComparisonProofState.Progress.CLOUD && "selected".equals(state.selectedId()),
                "Release preserves explicit progress and remote choice for Retry");
        check(!state.releaseClaim(claimed), "Repeated release is a no-op");
        ComparisonProofState.Ticket retry = state.capture(GAME, true, false);
        check(retry != null && retry.token > claimed.token, "A released claim allows an explicit fresh Finish retry");
        check("selected".equals(retry.snapshotId) && state.claimedCurrent(retry, GAME, true),
                "Retry captures the preserved selection with valid new proof");
        check(!state.releaseClaim(claimed), "An earlier completion cannot release the retry's claim");
        check(state.claimedCurrent(retry, GAME, true), "Old release leaves the retry worker untouched");
        ComparisonProofState other = state();
        ComparisonProofState.Ticket foreign = other.capture(GAME, true, false);
        check(!state.releaseClaim(foreign), "A foreign model's claim cannot release current work");
        check(state.claimedCurrent(retry, GAME, true), "Foreign release leaves current claim untouched");
        state.reset();
        state.bind(GAME);
        ComparisonProofState.Ticket replacement = state.capture(GAME, true, false);
        check(!state.releaseClaim(retry), "Reset invalidates a delayed release even after rebinding");
        check(state.claimedCurrent(replacement, GAME, true), "Delayed release preserves a new lifecycle's claim");
        check(state.releaseClaim(replacement), "Replacement worker can release its own claim");
        check(state.capture(GAME, true, false) != null, "Lifecycle replacement can also be retried explicitly");
    }

    private static void concurrentClaims() throws InterruptedException {
        for (boolean nonmodal : new boolean[]{false, true}) {
            ComparisonProofState state = state();
            ComparisonProofState.Ticket original = nonmodal ? null : state.openConflict();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            AtomicReference<ComparisonProofState.Ticket> winner = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable attempt = () -> {
                ready.countDown();
                try {
                    start.await();
                    ComparisonProofState.Ticket ticket = nonmodal
                            ? state.capture(GAME, true, false) : claim(state, original);
                    if (ticket != null) {
                        winners.incrementAndGet();
                        winner.set(ticket);
                    }
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            };
            Thread first = new Thread(attempt, "comparison-proof-first");
            Thread second = new Thread(attempt, "comparison-proof-second");
            first.start();
            second.start();
            ready.await();
            start.countDown();
            first.join();
            second.join();
            check(failure.get() == null, "Concurrent proof checks finish without thread failure");
            check(winners.get() == 1, "Concurrent " + (nonmodal ? "Finish" : "modal") + " claims have exactly one winner");
            check(state.claimedCurrent(winner.get(), GAME, true), "The concurrent winner retains the only claimed proof");
            check(state.dialog() == null, "Concurrent claim closes the visible modal exactly once");
        }
    }

    private static void failureRevocation() {
        ComparisonProofState state = state();
        ComparisonProofState.Ticket failed = state.capture(GAME, true, false);
        check(failed != null && state.progress() == null, "An identical Finish needs no invented progress choice");
        check(!state.bind(context()), "Same-context rerender before failure does not replace observation");
        check(state.claimedCurrent(failed, GAME, true), "Same-context rerender retains the pending Finish claim");
        check(state.invalidateFailure(failed, context(), true), "Current identical Finish failure revokes its observation");
        empty(state, "Failed identical Finish");
        check(!state.matches(GAME) && !state.claimedCurrent(failed, GAME, true),
                "Failure removes old proof so the host can require Refresh");
        check(state.capture(GAME, true, false) == null, "Failed Finish cannot retry against an unbound old observation");
        check(state.openConflict() == null, "Failure cannot reopen a modal against old observation");
        check(!state.releaseClaim(failed) && !state.invalidateFailure(failed, GAME, true),
                "Repeated completion or failure is harmless after revocation");
        ComparisonProofState.Context refreshed = new ComparisonProofState.Context(
                FACADE, CONNECTION, new EqualObject(), "root:game-a");
        check(state.capture(refreshed, true, false) == null, "Even a refreshed context must be bound before Retry");
        check(state.bind(refreshed), "Host binds the actual refreshed observation explicitly");
        ComparisonProofState.Ticket retry = state.capture(refreshed, true, false);
        check(retry != null && retry.token > failed.token && retry.context.equals(refreshed),
                "Refresh allows a fresh Finish claim using its new inspection");
        check(!state.invalidateFailure(failed, refreshed, true), "An old second failure cannot clear the refreshed claim");
        check(!state.invalidateFailure(failed, GAME, true), "Old failure context cannot restore or clear refreshed proof");
        check(state.matches(refreshed) && state.claimedCurrent(retry, refreshed, true),
                "Refreshed observation and worker survive stale second failure");

        state = state();
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
        ComparisonProofState.Ticket modal = state.openConflict();
        check(!state.invalidateFailure(modal, GAME, true), "An unclaimed modal is not a failed worker");
        check(state.dialog() == modal && current(state, modal), "Unclaimed failure cannot erase modal proof");
        failed = claim(state, modal);
        check(!state.invalidateFailure(null, GAME, true), "Null failure cannot revoke claimed work");
        check(!state.invalidateFailure(failed, null, true), "Failure without current context cannot revoke proof");
        check(!state.invalidateFailure(failed, GAME, false), "A failure after leaving comparison cannot clear state");
        check(state.matches(GAME) && state.claimedCurrent(failed, GAME, true),
                "Off-page failure leaves current observation and claim untouched");
        check(state.progress() == ComparisonProofState.Progress.CLOUD && "selected".equals(state.selectedId()),
                "Off-page failure preserves explicit selection");
        ComparisonProofState foreignState = state();
        ComparisonProofState.Ticket foreign = foreignState.capture(GAME, true, false);
        check(!state.invalidateFailure(foreign, GAME, true), "A foreign model's claim cannot revoke current state");
        check(state.claimedCurrent(failed, GAME, true), "Foreign failure leaves the owning worker current");
        check(state.invalidateFailure(failed, GAME, true),
                "Failure completion can revoke its own proof without a hard-busy input");
        empty(state, "Failed selected conflict");
        check(!state.matches(GAME), "Failure also clears the selected conflict's observation");

        ComparisonProofState.Context[] changes = {
                new ComparisonProofState.Context(new EqualObject(), CONNECTION, INSPECTION, "root:game-a"),
                new ComparisonProofState.Context(FACADE, new EqualObject(), INSPECTION, "root:game-a"),
                new ComparisonProofState.Context(FACADE, CONNECTION, new EqualObject(), "root:game-a"),
                new ComparisonProofState.Context(FACADE, CONNECTION, INSPECTION, "embedded:game-a")
        };
        String[] causes = {"facade", "account", "inspection", "backend"};
        for (int i = 0; i < changes.length; i++) {
            ComparisonProofState.Context changed = changes[i];
            String cause = causes[i];
            state = state();
            state.selectProgress(ComparisonProofState.Progress.CLOUD, "original");
            failed = state.capture(GAME, true, false);
            check(!state.invalidateFailure(failed, changed, true), cause + " mismatch rejects failure before rebinding");
            check(state.matches(GAME) && state.claimedCurrent(failed, GAME, true),
                    cause + " mismatch does not mutate currently bound proof");
            check(state.progress() == ComparisonProofState.Progress.CLOUD && "original".equals(state.selectedId()),
                    cause + " mismatch preserves the current choice");
            check(state.bind(changed), cause + " binds a new observation");
            state.selectProgress(ComparisonProofState.Progress.CLOUD, "new-head");
            retry = state.capture(changed, true, false);
            check(!state.invalidateFailure(failed, changed, true), cause + " new observation cannot be cleared by old failure");
            check(!state.invalidateFailure(failed, GAME, true), cause + " old callback context cannot clear newer work");
            check(state.matches(changed) && state.claimedCurrent(retry, changed, true),
                    cause + " new observation and claim survive stale failure");
            check(state.progress() == ComparisonProofState.Progress.CLOUD && "new-head".equals(state.selectedId()),
                    cause + " new selection survives stale failure");
        }

        state = state();
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
        modal = state.openConflict();
        check(state.cancel(modal.token, GAME, true, false), "Modal cancellation starts the stale-failure test");
        ComparisonProofState.Ticket newerModal = state.openConflict();
        check(!state.invalidateFailure(modal, GAME, true), "Cancelled modal cannot report a current worker failure");
        check(state.dialog() == newerModal && current(state, newerModal), "Cancelled failure preserves replacement modal");
        failed = claim(state, newerModal);
        check(state.releaseClaim(failed), "Completion releases claim before explicit retry");
        retry = state.capture(GAME, true, false);
        check(!state.invalidateFailure(failed, GAME, true), "Released claim cannot later fail the replacement worker");
        check(state.claimedCurrent(retry, GAME, true) && "selected".equals(state.selectedId()),
                "Late released failure preserves retry and selection");
        failed = retry;
        state.selectProgress(ComparisonProofState.Progress.LOCAL, null);
        retry = state.capture(GAME, true, false);
        check(!state.invalidateFailure(failed, GAME, true), "A new user choice replaces the old failure owner");
        check(state.claimedCurrent(retry, GAME, true)
                && state.progress() == ComparisonProofState.Progress.LOCAL && state.selectedId() == null,
                "Replaced failure preserves the new choice and claim");
        failed = retry;
        state.reset();
        state.bind(GAME);
        retry = state.capture(GAME, true, false);
        check(!state.invalidateFailure(failed, GAME, true), "Failure from a prior lifecycle cannot clear a new lifecycle");
        check(state.claimedCurrent(retry, GAME, true), "New lifecycle claim survives stale failure");
    }

    private static ComparisonProofState.Context refreshedContext() {
        return new ComparisonProofState.Context(FACADE, CONNECTION, new EqualObject(), "root:game-a");
    }

    private static void reinspectionFailureOwnership() {
        ComparisonProofState state = state();
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "old-head");
        ComparisonProofState.Ticket original = state.openConflict();
        ComparisonProofState.Context refreshed = refreshedContext();
        check(!state.beginReinspection(original, GAME, true), "An unclaimed modal cannot start owned reinspection");
        check(state.dialog() == original && current(state, original), "Rejected begin preserves modal proof");
        check(claim(state, original) == original, "A claimed operation can own reinspection");
        check(!state.beginReinspection(null, GAME, true), "Null cannot begin reinspection");
        check(!state.beginReinspection(original, refreshed, true), "Mismatched inspection cannot begin reinspection");
        check(!state.beginReinspection(original, GAME, false), "Off-page work cannot begin reinspection");
        check(state.claimedCurrent(original, GAME, true), "Rejected begin leaves the existing worker claim intact");
        check(state.beginReinspection(original, context(), true), "Exact claimed owner can begin a same-target re-read");
        empty(state, "Reinspection begin");
        check(!state.matches(GAME), "Reinspection removes the old observation");
        check(!state.claimedCurrent(original, GAME, true), "Failure-only owner no longer authorizes the original action");
        check(!state.beginReinspection(original, GAME, true), "Failure-only ownership cannot begin another reinspection");
        check(state.capture(GAME, true, false) == null, "Pending reinspection cannot create action proof");
        check(!state.finishReinspection(original, GAME, true), "Finish requires a genuinely new inspection identity");
        check(!state.finishReinspection(original, null, true), "Read failure before inspection cannot bind a failure context");
        check(!state.finishReinspection(original, refreshed, false), "Off-page finish does not bind reinspection");
        check(!state.invalidateFailure(original, GAME, true), "Pending reinspection cannot invalidate the discarded old observation");
        check(!state.invalidateFailure(original, refreshed, true), "Pending reinspection cannot invalidate an unbound new observation");
        check(!state.invalidateFailure(original, null, true), "Read failure without an inspection cannot manufacture a stale result");
        check(state.finishReinspection(original, refreshed, true), "Owning re-read binds the new observation for failure cleanup");
        empty(state, "Reinspection finish");
        check(state.matches(refreshed), "Finished reinspection binds its actual fresh context");
        check(!state.claimedCurrent(original, refreshed, true), "Transferred failure ownership never authorizes a new action");
        check(!state.current(original.token, refreshed, true, false, original.stage), "Original token cannot become a new modal token");
        check(state.claim(original.token, refreshed, true, false, original.stage) == null,
                "Original ticket cannot claim an action against reinspection");
        check(state.review(original, refreshed, true, false, true) == null,
                "Original ticket cannot preview or authorize a new merge review");
        check(!state.finishReinspection(original, refreshedContext(), true), "Duplicate finish cannot replace its completed context");
        check(!state.beginReinspection(original, refreshed, true), "Completed failure owner cannot start a new action read");
        ComparisonProofState.Context sameRefresh = new ComparisonProofState.Context(
                FACADE, CONNECTION, refreshed.inspection, new String("root:game-a"));
        check(!state.bind(sameRefresh), "Same reinspection rerender preserves failure ownership");
        check(!state.invalidateFailure(original, GAME, true), "Original context cannot invalidate reinspection");
        check(!state.invalidateFailure(original, refreshed, false), "Off-page post-recompare failure cannot clear new proof");
        check(state.matches(refreshed), "Rejected post-recompare failure preserves the fresh context");
        check(state.invalidateFailure(original, sameRefresh, true),
                "Identical completion failure after owned recompare revokes the fresh observation");
        empty(state, "Post-recompare identical failure");
        check(!state.matches(refreshed), "Post-recompare failure requires a real Refresh");
        check(state.capture(refreshed, true, false) == null, "Failure ownership does not allow retry on revoked reinspection");
        check(!state.invalidateFailure(original, refreshed, true) && !state.releaseClaim(original),
                "Repeated post-recompare failure or release cannot reuse revoked ownership");

        for (boolean completed : new boolean[]{false, true}) {
            state = state();
            original = state.capture(GAME, true, false);
            refreshed = refreshedContext();
            check(state.beginReinspection(original, GAME, true), "Release scenario begins reinspection");
            if (completed) check(state.finishReinspection(original, refreshed, true), "Release scenario finishes reinspection");
            check(state.releaseClaim(original), "Routine completion can release " + (completed ? "completed" : "pending") + " failure ownership");
            check(!state.releaseClaim(original), "Failure-owner release is single-use");
            check(!state.finishReinspection(original, refreshed, true), "Released failure owner cannot complete or replace reinspection");
            check(!state.invalidateFailure(original, refreshed, true), "Released failure owner cannot revoke later state");
            check(state.matches(refreshed) == completed, "Release preserves only an already completed reinspection context");
            empty(state, "Reinspection release");
            if (!completed) state.bind(refreshed);
            ComparisonProofState.Ticket freshClaim = state.capture(refreshed, true, false);
            check(freshClaim != null && freshClaim.token > original.token, "A later explicit action receives a fresh ticket");
            check(!state.releaseClaim(original) && !state.invalidateFailure(original, refreshed, true),
                    "Late original completion cannot release or fail a fresh action");
            check(state.claimedCurrent(freshClaim, refreshed, true), "Fresh action retains its independent permission");
        }
    }

    private static void reinspectionStaleness() {
        ComparisonProofState.Context[] changedSources = {
                new ComparisonProofState.Context(new EqualObject(), CONNECTION, new EqualObject(), "root:game-a"),
                new ComparisonProofState.Context(FACADE, new EqualObject(), new EqualObject(), "root:game-a"),
                new ComparisonProofState.Context(FACADE, CONNECTION, new EqualObject(), "embedded:game-a")
        };
        String[] causes = {"new facade", "new account", "new backend"};
        for (int i = 0; i < changedSources.length; i++) {
            ComparisonProofState state = state();
            ComparisonProofState.Ticket original = state.capture(GAME, true, false);
            ComparisonProofState.Context refreshed = refreshedContext();
            check(state.beginReinspection(original, GAME, true), causes[i] + " test starts owned reinspection");
            check(!state.finishReinspection(original, changedSources[i], true), causes[i] + " cannot inherit original failure ownership");
            check(!state.matches(changedSources[i]), causes[i] + " rejected finish does not bind its context");
            check(state.finishReinspection(original, refreshed, true), causes[i] + " rejected callback preserves correct pending ownership");
            check(!state.invalidateFailure(original, changedSources[i], true), causes[i] + " mismatched failure is ignored");
            check(state.matches(refreshed), causes[i] + " mismatched failure leaves actual reinspection intact");
        }

        for (boolean completed : new boolean[]{false, true}) {
            for (String cause : new String[]{"pause", "leave", "new selection", "another inspection", "new account", "new facade", "new backend"}) {
                ComparisonProofState state = state();
                state.selectProgress(ComparisonProofState.Progress.CLOUD, "old-head");
                ComparisonProofState.Ticket original = state.capture(GAME, true, false);
                ComparisonProofState.Context refreshed = refreshedContext();
                check(state.beginReinspection(original, GAME, true), cause + " begins reinspection before invalidation");
                if (completed) check(state.finishReinspection(original, refreshed, true), cause + " completes reinspection before invalidation");
                ComparisonProofState.Context replacement = refreshedContext();
                if (cause.equals("pause") || cause.equals("leave")) {
                    state.reset();
                    state.bind(replacement);
                } else if (cause.equals("new selection")) {
                    state.selectProgress(ComparisonProofState.Progress.LOCAL, null);
                    check(!state.finishReinspection(original, refreshed, true), "Explicit selection revokes even a pending read owner");
                    check(!state.invalidateFailure(original, refreshed, true), "Explicit selection revokes completed failure ownership");
                    if (completed) replacement = refreshed;
                    else state.bind(replacement);
                } else {
                    if (cause.equals("new account")) replacement = changedSources[1];
                    if (cause.equals("new facade")) replacement = changedSources[0];
                    if (cause.equals("new backend")) replacement = changedSources[2];
                    state.bind(replacement);
                }
                state.selectProgress(ComparisonProofState.Progress.CLOUD, "new-head");
                ComparisonProofState.Ticket newer = state.capture(replacement, true, false);
                check(!state.beginReinspection(original, GAME, true), cause + " old begin cannot touch the replacement claim");
                check(!state.finishReinspection(original, refreshed, true), cause + " old finish cannot bind over newer result");
                check(!state.invalidateFailure(original, refreshed, true), cause + " old failure cannot clear newer result");
                check(!state.invalidateFailure(original, replacement, true), cause + " old owner cannot adopt newer failure context");
                check(!state.releaseClaim(original), cause + " old completion cannot release replacement ownership");
                check(state.matches(replacement) && state.claimedCurrent(newer, replacement, true),
                        cause + " preserves the newer observation and action claim");
                check(state.progress() == ComparisonProofState.Progress.CLOUD && "new-head".equals(state.selectedId()),
                        cause + " preserves the newer explicit choice");
            }
        }

        ComparisonProofState state = state();
        ComparisonProofState.Ticket original = state.capture(GAME, true, false);
        ComparisonProofState.Context refreshed = refreshedContext();
        check(state.beginReinspection(original, GAME, true), "Foreign owner test begins reinspection");
        ComparisonProofState other = state();
        ComparisonProofState.Ticket foreign = other.capture(GAME, true, false);
        check(!state.finishReinspection(foreign, refreshed, true), "Foreign ticket cannot finish pending reinspection");
        check(!state.releaseClaim(foreign), "Foreign ticket cannot release pending failure ownership");
        check(state.finishReinspection(original, refreshed, true), "Foreign callbacks leave the original owner usable");
        check(!state.invalidateFailure(foreign, refreshed, true), "Foreign ticket cannot fail a completed reinspection");
        check(!state.releaseClaim(foreign), "Foreign ticket cannot release completed failure ownership");
        ComparisonProofState.Ticket newModal = state.openConflict();
        check(!state.invalidateFailure(original, refreshed, true), "New explicit modal revokes prior failure ownership");
        check(state.dialog() == newModal && state.current(newModal.token, refreshed, true, false, newModal.stage),
                "Stale failure cannot clear the new explicit modal");
        check(state.selectRemote("new-head") != null, "A new modal may make its own explicit remote selection");
        check(!state.releaseClaim(original) && "new-head".equals(state.selectedId()),
                "Original completion cannot erase the new remote selection");
    }

    private static boolean uninspectedFailure(ComparisonProofState state, ComparisonProofState.Ticket owner) {
        return state.invalidateUninspectedFailure(owner, FACADE, CONNECTION, "root:game-a", true);
    }

    private static void uninspectedFailureScope() {
        ComparisonProofState state = state();
        state.selectProgress(ComparisonProofState.Progress.CLOUD, "selected");
        ComparisonProofState.Ticket original = state.openConflict();
        check(!uninspectedFailure(state, original), "An ordinary modal cannot report an uninspected re-read failure");
        check(state.dialog() == original, "Uninspected failure cannot erase an unclaimed modal");
        check(claim(state, original) == original, "Uninspected failure scenario claims the original action");
        check(!uninspectedFailure(state, original), "A normal action claim is not pending reinspection ownership");
        check(state.claimedCurrent(original, GAME, true), "Rejected uninspected failure preserves ordinary action proof");
        check(state.beginReinspection(original, GAME, true), "Original action begins a re-read that may fail before inspection");
        check(!state.invalidateFailure(original, null, true), "Normal failure validation still rejects a missing inspection");
        check(!state.invalidateUninspectedFailure(null, FACADE, CONNECTION, "root:game-a", true),
                "Null owner cannot report uninspected failure");
        ComparisonProofState other = state();
        ComparisonProofState.Ticket foreign = other.capture(GAME, true, false);
        check(!uninspectedFailure(state, foreign), "Foreign ticket cannot report another read's uninspected failure");
        check(!state.invalidateUninspectedFailure(original, new EqualObject(), CONNECTION, "root:game-a", true),
                "Facade value equality cannot substitute for exact re-read target");
        check(!state.invalidateUninspectedFailure(original, FACADE, new EqualObject(), "root:game-a", true),
                "Connection value equality cannot substitute for exact account");
        check(!state.invalidateUninspectedFailure(original, null, CONNECTION, "root:game-a", true),
                "Missing facade cannot report pending read failure");
        check(!state.invalidateUninspectedFailure(original, FACADE, null, "root:game-a", true),
                "Missing connection cannot report pending read failure");
        check(!state.invalidateUninspectedFailure(original, FACADE, CONNECTION, "embedded:game-a", true),
                "Different backend cannot report pending read failure");
        check(!state.invalidateUninspectedFailure(original, FACADE, CONNECTION, null, true),
                "Missing backend key cannot report pending read failure");
        check(!state.invalidateUninspectedFailure(original, FACADE, CONNECTION, "root:game-a", false),
                "Off-page pending failure cannot reset state");
        check(!state.claimedCurrent(original, GAME, true) && state.capture(GAME, true, false) == null,
                "Pending failure-only ownership grants no old or new action permission");
        check(state.invalidateUninspectedFailure(original, FACADE, CONNECTION, new String("root:game-a"), true),
                "Exact pending target can report failure before any new inspection exists");
        empty(state, "Uninspected re-read failure");
        check(!state.matches(GAME), "Uninspected failure leaves no usable old observation");
        check(!uninspectedFailure(state, original), "Uninspected failure is consumed exactly once");
        check(!state.releaseClaim(original), "Consumed uninspected failure leaves no lingering owner to release");
        ComparisonProofState.Context refreshed = refreshedContext();
        check(!state.finishReinspection(original, refreshed, true), "Consumed failed read cannot bind a delayed inspection");
        check(state.capture(refreshed, true, false) == null, "A real refreshed observation must be bound before Retry");
        state.bind(refreshed);
        ComparisonProofState.Ticket newer = state.capture(refreshed, true, false);
        check(!uninspectedFailure(state, original), "Repeated old failure cannot clear a fresh action");
        check(state.matches(refreshed) && state.claimedCurrent(newer, refreshed, true),
                "Fresh inspection and claim survive an old uninspected failure");

        state = state();
        original = state.capture(GAME, true, false);
        refreshed = refreshedContext();
        check(state.beginReinspection(original, GAME, true)
                && state.finishReinspection(original, refreshed, true), "Completed read test installs actual reinspection proof");
        check(!uninspectedFailure(state, original), "A completed reinspection cannot use the missing-inspection failure route");
        check(state.matches(refreshed), "Uninspected failure cannot erase a completed reinspection");
        check(state.invalidateFailure(original, refreshed, true), "Completed reinspection retains only its ordinary scoped failure route");

        for (String cause : new String[]{"pause", "rotation", "new selection", "new inspection", "new account", "new facade", "new backend", "normal release"}) {
            state = state();
            original = state.capture(GAME, true, false);
            check(state.beginReinspection(original, GAME, true), cause + " starts pending reinspection");
            ComparisonProofState.Context replacement = refreshedContext();
            switch (cause) {
                case "pause":
                case "rotation":
                    state.reset();
                    break;
                case "new selection":
                    state.selectProgress(ComparisonProofState.Progress.LOCAL, null);
                    break;
                case "new account":
                    replacement = new ComparisonProofState.Context(FACADE, new EqualObject(), new EqualObject(), "root:game-a");
                    state.bind(replacement);
                    break;
                case "new facade":
                    replacement = new ComparisonProofState.Context(new EqualObject(), CONNECTION, new EqualObject(), "root:game-a");
                    state.bind(replacement);
                    break;
                case "new backend":
                    replacement = new ComparisonProofState.Context(FACADE, CONNECTION, new EqualObject(), "embedded:game-a");
                    state.bind(replacement);
                    break;
                case "new inspection":
                    state.bind(replacement);
                    break;
                case "normal release":
                    check(state.releaseClaim(original), "Normal completion releases pending uninspected failure ownership");
                    break;
                default:
                    throw new AssertionError(cause);
            }
            check(!uninspectedFailure(state, original), cause + " revokes the pending uninspected failure route");
            check(!state.finishReinspection(original, replacement, true), cause + " also revokes a delayed finish");
            state.bind(replacement);
            state.selectProgress(ComparisonProofState.Progress.CLOUD, "replacement-head");
            newer = state.capture(replacement, true, false);
            check(!state.invalidateUninspectedFailure(original, replacement.facade,
                    replacement.connection, replacement.backendKey, true),
                    cause + " old candidate cannot adopt the replacement target");
            check(state.matches(replacement) && state.claimedCurrent(newer, replacement, true),
                    cause + " preserves the replacement observation and claim");
            check("replacement-head".equals(state.selectedId())
                    && state.progress() == ComparisonProofState.Progress.CLOUD,
                    cause + " preserves the new explicit user selection");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        contextIdentity();
        explicitChoiceAndRerender();
        cancellationAndLifecycle();
        changedObservations();
        mergeReview();
        claimAndFinish();
        foregroundAndBusy();
        releaseAndRetry();
        concurrentClaims();
        failureRevocation();
        reinspectionFailureOwnership();
        reinspectionStaleness();
        uninspectedFailureScope();
        System.out.println("ComparisonProofStateChecks: " + checks + " checks passed (production model; no UI or core writes)");
    }
}
