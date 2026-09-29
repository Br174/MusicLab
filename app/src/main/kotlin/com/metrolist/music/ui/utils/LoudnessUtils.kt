package com.metrolist.music.ui.utils

import com.metrolist.music.constants.LoudnessLevel

fun getLoudnessLevelLabel(level: LoudnessLevel): String {
    val name = level.name.lowercase().replaceFirstChar { it.titlecase() }
    val lufs = if (level.targetLufs % 1f == 0f) level.targetLufs.toInt().toString() else level.targetLufs.toString()
    return "$name ($lufs LUFS)"
}
