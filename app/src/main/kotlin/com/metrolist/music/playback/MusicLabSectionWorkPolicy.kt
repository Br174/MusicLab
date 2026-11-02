package com.metrolist.music.playback

/** Pure decision gate shared by each section's already-existing loader. */
internal object MusicLabSectionWorkPolicy {
    fun shouldLoad(hasCompleteSnapshot: Boolean, phase: MusicLabSectionWorkCheckpoint.Phase): Boolean =
        !hasCompleteSnapshot

    fun shouldResumeInterrupted(
        phase: MusicLabSectionWorkCheckpoint.Phase,
        hasStarted: Boolean,
        resultAlreadyComplete: Boolean,
    ): Boolean = hasStarted && !resultAlreadyComplete &&
        phase != MusicLabSectionWorkCheckpoint.Phase.COMPLETE

    fun shouldPersistRow(
        currentAlbumId: Int?,
        requestedAlbumId: Int,
        index: Int,
        count: Int,
    ): Boolean = currentAlbumId == requestedAlbumId && index in 0 until count
}
