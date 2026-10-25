/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Detects "Application Not Responding" situations — the system dialog that says
 * *"L'app non risponde, attendi o chiudi"* — and routes them to [CrashReporter].
 *
 * Strategy: a daemon thread posts a Runnable to the main Looper, then sleeps. If the
 * main thread doesn't execute the Runnable within [ANR_THRESHOLD_MS], the main thread
 * is stuck and we capture its stack trace and dispatch a non-fatal report. The main
 * thread is *not* killed — Android may still show its own ANR dialog, and the user
 * may dismiss it and continue using the app.
 *
 * The watchdog is silent while a debugger is attached so breakpoints don't generate
 * false positives.
 */
object AnrWatchdog {

    /** Time main thread must be unresponsive before we consider it an ANR. */
    private const val ANR_THRESHOLD_MS = 5_000L

    /** After detecting an ANR, wait this long before checking again to avoid a flood. */
    private const val POST_ANR_BACKOFF_MS = 30_000L
    private const val PREF = "musiclab_anr_local_diagnostic"
    private const val KEY_REPORT = "last_anr"
    @Volatile private var appContext: Context? = null
    /** Returns only technical stack-frame symbols; never songs, URLs or settings. */
    fun latestReport(context: Context): String =
        runCatching {
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_REPORT, null)
        }.getOrNull() ?: "Nessun blocco registrato dal watchdog. Prova a riaprire MusicLab dopo l'ANR."

    @Volatile
    private var ticked = true

    @Volatile
    private var started = false

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        appContext = context.applicationContext
        if (started) return
        started = true
        Thread(::run).apply {
            name = "Meld-ANR-Watchdog"
            isDaemon = true
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun run() {
        while (!Thread.currentThread().isInterrupted) {
            ticked = false
            try {
                mainHandler.post { ticked = true }
            } catch (t: Throwable) {
                Timber.tag("AnrWatchdog").w(t, "Failed to post heartbeat")
                sleepQuietly(ANR_THRESHOLD_MS)
                continue
            }

            if (!sleepQuietly(ANR_THRESHOLD_MS)) return

            if (ticked) continue
            if (debuggerActive()) continue

            val mainStack = try {
                Looper.getMainLooper().thread.stackTrace
            } catch (t: Throwable) {
                Timber.tag("AnrWatchdog").w(t, "Failed to capture main thread stack")
                emptyArray()
            }

            if (mainStack.isNotEmpty()) {
                // LAB58: retain one locally inspectable stack even when
                // crash-upload credentials are absent. Store from this daemon,
                // never on Main; metadata-only, no private song/title content.
                val stackReport = buildString {
                    appendLine("MusicLab LAB58 · ANR main thread")
                    appendLine("Soglia blocco: ${ANR_THRESHOLD_MS}ms")
                    appendLine("Istante UTC ms: ${System.currentTimeMillis()}")
                    mainStack.take(22).forEach { frame ->
                        appendLine(
                            frame.className + "." + frame.methodName +
                                ":" + frame.lineNumber
                        )
                    }
                }.take(3500)
                runCatching {
                    appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        ?.edit()?.putString(KEY_REPORT, stackReport)?.apply()
                }
                Timber.tag("AnrWatchdog").w(
                    "Main thread blocked >${ANR_THRESHOLD_MS}ms — top: %s.%s",
                    mainStack[0].className.substringAfterLast('.'),
                    mainStack[0].methodName,
                )
                CrashReporter.reportAnr(mainStack, ANR_THRESHOLD_MS)
            }

            // Back off so a single sustained ANR doesn't generate a stream of reports.
            if (!sleepQuietly(POST_ANR_BACKOFF_MS)) return
        }
    }

    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }

    private fun debuggerActive(): Boolean = try {
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    } catch (_: Throwable) {
        false
    }
}
