package com.metrolist.music.playback

import org.junit.Assert.*
import org.junit.Test

class MusicLabSectionWorkPolicyTest {
    @Test fun completedResultNeverRestartsOnNavigation() {
        assertFalse(MusicLabSectionWorkPolicy.shouldLoad(true,
            MusicLabSectionWorkCheckpoint.Phase.COMPLETE))
        assertFalse(MusicLabSectionWorkPolicy.shouldLoad(true,
            MusicLabSectionWorkCheckpoint.Phase.STANDBY))
        assertFalse(MusicLabSectionWorkPolicy.shouldLoad(false,
            MusicLabSectionWorkCheckpoint.Phase.COMPLETE))
    }
    @Test fun cancelledOrCrashedIncompleteResultCanResume() {
        assertTrue(MusicLabSectionWorkPolicy.shouldResumeInterrupted(
            MusicLabSectionWorkCheckpoint.Phase.STANDBY, true, false))
        assertTrue(MusicLabSectionWorkPolicy.shouldResumeInterrupted(
            MusicLabSectionWorkCheckpoint.Phase.RUNNING, true, false))
        assertFalse(MusicLabSectionWorkPolicy.shouldResumeInterrupted(
            MusicLabSectionWorkCheckpoint.Phase.COMPLETE, true, false))
        assertFalse(MusicLabSectionWorkPolicy.shouldResumeInterrupted(
            MusicLabSectionWorkCheckpoint.Phase.STANDBY, false, false))
        assertFalse(MusicLabSectionWorkPolicy.shouldResumeInterrupted(
            MusicLabSectionWorkCheckpoint.Phase.STANDBY, true, true))
    }
    @Test fun albumBoundaryAndOutOfRangeResultsNeverPersist() {
        assertTrue(MusicLabSectionWorkPolicy.shouldPersistRow(24,24,0,32))
        assertTrue(MusicLabSectionWorkPolicy.shouldPersistRow(24,24,31,32))
        assertFalse(MusicLabSectionWorkPolicy.shouldPersistRow(24,25,1,32))
        assertFalse(MusicLabSectionWorkPolicy.shouldPersistRow(null,24,1,32))
        assertFalse(MusicLabSectionWorkPolicy.shouldPersistRow(24,24,32,32))
        assertFalse(MusicLabSectionWorkPolicy.shouldPersistRow(24,24,-1,32))
    }
}
