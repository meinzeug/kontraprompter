package de.kontrakurs.kontraprompter.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    private val idle = NavigationSnapshot(1, SessionRole.NONE, Take.NONE, Take.NONE, Upload.NONE)
    private fun open(stack: NavigationStack, destination: Destination, snapshot: NavigationSnapshot = idle): NavigationStack =
        (NavigationPolicy.plan(stack, NavigationRequest.Open(destination), snapshot) as NavigationDecision.Planned).after
    private fun blocked(stack: NavigationStack, request: NavigationRequest, snapshot: NavigationSnapshot): Set<NavigationBlock> =
        (NavigationPolicy.plan(stack, request, snapshot) as NavigationDecision.Blocked).reasons

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

    @Test fun recordingOrUncertainRecordingCannotBeClosedByBackOrExit() {
        val connection = open(NavigationStack.start(), Destination.CONNECTION)
        for (take in listOf(Take.ACTIVE, Take.UNKNOWN)) {
            val camera = idle.copy(session = SessionRole.CAMERA, localTake = take)
            for (request in listOf(NavigationRequest.Back, NavigationRequest.Exit, NavigationRequest.Open(Destination.SCRIPTS))) {
                val reasons = blocked(connection, request, camera)
                assertTrue(reasons.contains(NavigationBlock.LOCAL_TAKE_ACTIVE_OR_UNKNOWN))
                assertTrue(reasons.contains(NavigationBlock.SESSION_MUST_CLOSE))
            }
        }
    }

    @Test fun recorderStillClosingAfterSessionReferenceDisappearsBlocksExit() {
        val stack = NavigationStack.start()
        val closing = idle.copy(session = SessionRole.NONE, localTake = Take.UNKNOWN)
        assertEquals(setOf(NavigationBlock.LOCAL_TAKE_ACTIVE_OR_UNKNOWN), blocked(stack, NavigationRequest.Exit, closing))
    }

    @Test fun connectedCameraRequiresExplicitSessionCloseEvenWhenIdle() {
        val connection = open(NavigationStack.start(), Destination.CONNECTION)
        val camera = idle.copy(session = SessionRole.CAMERA)
        assertEquals(setOf(NavigationBlock.SESSION_MUST_CLOSE), blocked(connection, NavigationRequest.Back, camera))
        assertTrue(NavigationPolicy.plan(connection, NavigationRequest.Back, idle) is NavigationDecision.Planned)
    }

    @Test fun prompterMayOpenScriptsWithoutClosingCameraSessionButCannotDisconnectAnActiveOrUnknownRemoteTake() {
        val connection = open(NavigationStack.start(), Destination.CONNECTION)
        for (take in listOf(Take.ACTIVE, Take.UNKNOWN)) {
            val prompter = idle.copy(session = SessionRole.PROMPTER, remoteTake = take)
            val scripts = open(connection, Destination.SCRIPTS, prompter)
            assertEquals(Destination.SCRIPTS, scripts.current)
            val reasons = blocked(connection, NavigationRequest.Back, prompter)
            assertTrue(reasons.contains(NavigationBlock.REMOTE_TAKE_ACTIVE_OR_UNKNOWN))
            assertTrue(reasons.contains(NavigationBlock.SESSION_MUST_CLOSE))
        }
    }

    @Test fun runningOrCancellingUploadCannotBeLeftAndBothProtectorsAreReported() {
        val upload = open(open(NavigationStack.start(), Destination.RECORDINGS), Destination.UPLOAD)
        for (phase in listOf(Upload.RUNNING, Upload.CANCELLING)) {
            val state = idle.copy(session = SessionRole.CAMERA, localTake = Take.ACTIVE, upload = phase)
            val reasons = blocked(upload, NavigationRequest.Exit, state)
            assertTrue(reasons.contains(NavigationBlock.UPLOAD_RUNNING_OR_CANCELLING))
            assertTrue(reasons.contains(NavigationBlock.LOCAL_TAKE_ACTIVE_OR_UNKNOWN))
            assertTrue(reasons.contains(NavigationBlock.SESSION_MUST_CLOSE))
            assertEquals(setOf(NavigationBlock.UPLOAD_RUNNING_OR_CANCELLING), blocked(upload, NavigationRequest.Back, state))
        }
    }

    @Test fun uncertainUploadCanLeaveWithoutImplicitRetryOrSuccess() {
        val upload = open(NavigationStack.start(), Destination.UPLOAD)
        val plan = NavigationPolicy.plan(upload, NavigationRequest.Back, idle.copy(upload = Upload.UNCERTAIN)) as NavigationDecision.Planned
        assertEquals(Destination.ROLES, plan.after.current)
        assertFalse(plan.exit)
    }

    @Test fun plannedBackIsInvalidAfterSessionRecordingUploadOrStackChanges() {
        val stack = open(NavigationStack.start(), Destination.SCRIPTS)
        val plan = NavigationPolicy.plan(stack, NavigationRequest.Back, idle) as NavigationDecision.Planned
        assertTrue(NavigationPolicy.revalidate(plan, stack, idle))
        assertFalse(NavigationPolicy.revalidate(plan, stack, idle.copy(epoch = 2)))
        assertFalse(NavigationPolicy.revalidate(plan, stack, idle.copy(localTake = Take.ACTIVE)))
        assertFalse(NavigationPolicy.revalidate(plan, stack, idle.copy(upload = Upload.RUNNING)))
        assertFalse(NavigationPolicy.revalidate(plan, stack, idle.copy(session = SessionRole.CAMERA)))
        assertFalse(NavigationPolicy.revalidate(plan, open(stack, Destination.EDITOR), idle))
    }
}
