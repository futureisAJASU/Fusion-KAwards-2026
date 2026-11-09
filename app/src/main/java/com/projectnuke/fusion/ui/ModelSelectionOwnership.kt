package com.projectnuke.fusion.ui

/** Main-thread selection authority: availability completion cannot supersede a later intent. */
internal class ModelSelectionOwnership {
    private var revision = 0L
    fun userSelected() { revision++ }
    fun beginDownload(): Long = ++revision
    fun canSelect(downloadRevision: Long): Boolean = downloadRevision == revision
}
