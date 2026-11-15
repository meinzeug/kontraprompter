package de.kontrakurs.kontraprompter.navigation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    private val idle = NavigationSnapshot(1, SessionRole.NONE, Take.NONE, Take.NONE, Upload.NONE, UploadOwner.NONE)
    private fun open(stack: NavigationStack, destination: Destination, snapshot: NavigationSnapshot = idle): NavigationStack =
        (NavigationPolicy.plan(stack, NavigationRequest.Open(destination), snapshot) as NavigationDecision.Planned).after
    private fun blocked(stack: NavigationStack, request: NavigationRequest, snapshot: NavigationSnapshot): Set<NavigationBlock> =
        (NavigationPolicy.plan(stack, request, snapshot) as NavigationDecision.Blocked).reasons
    private fun ready(fence: NavigationFence, request: NavigationRequest) = (fence.prepare(request) as NavigationFence.PrepareResult.Ready).ticket

    @Test fun backstackPreservesOrderAndAvoidsDuplicateCurrentDestination() {
        val start = NavigationStack.start()
        val scripts = open(start, Destination.SCRIPTS)
        val editor = open(scripts, Destination.EDITOR)
        assertEquals(editor, open(editor, Destination.EDITOR))
        val back = NavigationPolicy.plan(editor, NavigationRequest.Back, idle) as NavigationDecision.Planned
        assertEquals(listOf(Destination.ROLES, Destination.SCRIPTS), back.after.asList())
        assertFalse(back.exit)
        assertEquals(listOf(Destination.ROLES), start.asList())
    }

    @Test fun rootBackPlansExitWithoutDiscardingTheRoot() {
        val stack = NavigationStack.start()
        val plan = NavigationPolicy.plan(stack, NavigationRequest.Back, idle) as NavigationDecision.Planned
        assertTrue(plan.exit)
        assertEquals(stack, plan.after)
    }

    @Test fun recordingOrUncertainRecordingCannotBeClosedByBackExitOrRoleSwitch() {
        val connection = open(NavigationStack.start(), Destination.CONNECTION)
        for (take in listOf(Take.ACTIVE, Take.UNKNOWN)) {
            val camera = idle.copy(session = SessionRole.CAMERA, localTake = take)
            for (request in listOf(NavigationRequest.Back, NavigationRequest.Exit, NavigationRequest.Open(Destination.SCRIPTS), NavigationRequest.SwitchRole(NewRole.PROMPTER))) {
                val reasons = blocked(connection, request, camera)
                assertTrue(reasons.contains(NavigationBlock.LOCAL_TAKE_ACTIVE_OR_UNKNOWN))
                assertTrue(reasons.contains(NavigationBlock.SESSION_MUST_CLOSE))
            }
        }
    }

    @Test fun roleSwitchFromAnotherPageCannotDiscardPairedClientOrRemoteTake() {
        val scripts = open(NavigationStack.start(), Destination.SCRIPTS)
        val client = idle.copy(session = SessionRole.PROMPTER, remoteTake = Take.ACTIVE)
        val reasons = blocked(scripts, NavigationRequest.SwitchRole(NewRole.CAMERA), client)
        assertTrue(reasons.contains(NavigationBlock.SESSION_MUST_CLOSE))
        assertTrue(reasons.contains(NavigationBlock.REMOTE_TAKE_ACTIVE_OR_UNKNOWN))
        assertEquals(setOf(NavigationBlock.SESSION_MUST_CLOSE), blocked(scripts, NavigationRequest.SwitchRole(NewRole.CAMERA), client.copy(remoteTake = Take.NONE)))
    }

    @Test fun recorderStillClosingAfterSessionReferenceDisappearsBlocksExit() {
        assertEquals(setOf(NavigationBlock.LOCAL_TAKE_ACTIVE_OR_UNKNOWN), blocked(NavigationStack.start(), NavigationRequest.Exit, idle.copy(localTake = Take.UNKNOWN)))
    }

    @Test fun clientMayOpenScriptsWithoutClosingSessionButCannotBackOutWithUnknownRemoteTake() {
        val connection = open(NavigationStack.start(), Destination.CONNECTION)
        val client = idle.copy(session = SessionRole.PROMPTER, remoteTake = Take.UNKNOWN)
        assertEquals(Destination.SCRIPTS, open(connection, Destination.SCRIPTS, client).current)
        val reasons = blocked(connection, NavigationRequest.Back, client)
        assertTrue(reasons.contains(NavigationBlock.REMOTE_TAKE_ACTIVE_OR_UNKNOWN))
        assertTrue(reasons.contains(NavigationBlock.SESSION_MUST_CLOSE))
    }

    @Test fun uploadMustRetainItsActualOwnerAcrossEveryTransition() {
        val upload = open(open(NavigationStack.start(), Destination.RECORDINGS), Destination.UPLOAD)
        val scripts = open(NavigationStack.start(), Destination.SCRIPTS)
        for (phase in listOf(Upload.RUNNING, Upload.CANCELLING)) {
            val screenOwner = idle.copy(upload = phase, uploadOwner = UploadOwner.UPLOAD_SCREEN)
            assertTrue(blocked(upload, NavigationRequest.Back, screenOwner).contains(NavigationBlock.UPLOAD_RUNNING_OR_CANCELLING))
            // A screen-scoped owner cannot be assumed to survive when its screen is absent.
            assertTrue(blocked(scripts, NavigationRequest.Open(Destination.EDITOR), screenOwner).contains(NavigationBlock.UPLOAD_STATE_OR_OWNER_UNKNOWN))
            assertTrue(blocked(scripts, NavigationRequest.Back, screenOwner.copy(uploadOwner = UploadOwner.UNKNOWN)).contains(NavigationBlock.UPLOAD_STATE_OR_OWNER_UNKNOWN))
            val stableOwner = screenOwner.copy(uploadOwner = UploadOwner.ACTIVITY)
            assertTrue(NavigationPolicy.plan(upload, NavigationRequest.Back, stableOwner) is NavigationDecision.Planned)
            assertTrue(blocked(scripts, NavigationRequest.Exit, stableOwner).contains(NavigationBlock.UPLOAD_RUNNING_OR_CANCELLING))
        }
    }

    @Test fun unknownUploadBlocksAndUncertainUploadNeverImpliesSuccessOrRetry() {
        val upload = open(NavigationStack.start(), Destination.UPLOAD)
        assertEquals(setOf(NavigationBlock.UPLOAD_STATE_OR_OWNER_UNKNOWN), blocked(upload, NavigationRequest.Back, idle.copy(upload = Upload.UNKNOWN, uploadOwner = UploadOwner.UNKNOWN)))
        val plan = NavigationPolicy.plan(upload, NavigationRequest.Back, idle.copy(upload = Upload.UNCERTAIN)) as NavigationDecision.Planned
        assertEquals(Destination.ROLES, plan.after.current)
        assertFalse(plan.exit)
    }

    @Test fun restoredProcessStartsUnknownAndOldTicketsCannotCommitInNewFence() {
        val original = NavigationFence(idle)
        val old = ready(original, NavigationRequest.Open(Destination.SCRIPTS))
        val restored = NavigationFence()
        assertFalse(restored.commit(old))
        assertTrue(restored.prepare(NavigationRequest.Exit) is NavigationFence.PrepareResult.Blocked)
        assertTrue(restored.publish(idle.copy(epoch = 2)))
        assertFalse(restored.commit(old))
    }

    @Test fun predictiveBackCancellationAndSupersedingPlanInvalidateTickets() {
        val fence = NavigationFence(idle)
        assertTrue(fence.commit(ready(fence, NavigationRequest.Open(Destination.SCRIPTS))))
        val gesture = ready(fence, NavigationRequest.Back)
        assertTrue(fence.cancel(gesture))
        assertFalse(fence.commit(gesture))
        assertEquals(Destination.SCRIPTS, fence.current().current)
        val old = ready(fence, NavigationRequest.Back)
        val newer = ready(fence, NavigationRequest.Back)
        assertFalse(fence.commit(old))
        assertTrue(fence.commit(newer))
        assertFalse(fence.commit(newer))
    }

    @Test fun observationPublishedBetweenGestureAndCommitWinsUnderSharedFence() {
        val fence = NavigationFence(idle)
        assertTrue(fence.commit(ready(fence, NavigationRequest.Open(Destination.CONNECTION))))
        val old = ready(fence, NavigationRequest.Back)
        val published = CountDownLatch(1)
        val worker = Thread {
            assertTrue(fence.publish(idle.copy(epoch = 2, session = SessionRole.CAMERA, localTake = Take.ACTIVE)))
            published.countDown()
        }
        worker.start()
        assertTrue(published.await(5, TimeUnit.SECONDS))
        assertFalse(fence.commit(old))
        assertEquals(Destination.CONNECTION, fence.current().current)
        assertFalse(fence.publish(idle.copy(epoch = 1)))
        assertTrue(fence.prepare(NavigationRequest.Back) is NavigationFence.PrepareResult.Blocked)
        worker.join()
    }
}
