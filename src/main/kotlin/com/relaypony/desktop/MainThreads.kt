package com.relaypony.desktop

import com.relaypony.session.MainThread
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * The session module's [MainThread] for the GUI: the Swing event thread, where Compose Desktop runs
 * its composition, so WAN callbacks can touch Compose state directly. Delays run on a timer thread
 * and hop onto the event thread when they fire.
 */
class SwingMainThread : MainThread {
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relaypony-ui-timer").apply { isDaemon = true } }
    private val pending = HashMap<Runnable, MutableList<ScheduledFuture<*>>>()

    override fun post(r: Runnable) = SwingUtilities.invokeLater(r)

    override fun postDelayed(r: Runnable, delayMs: Long) {
        synchronized(pending) {
            lateinit var f: ScheduledFuture<*>
            f = timer.schedule({
                val live = synchronized(pending) { pending[r]?.remove(f) == true }
                if (live) SwingUtilities.invokeLater(r)
            }, delayMs, TimeUnit.MILLISECONDS)
            pending.getOrPut(r) { ArrayList() }.add(f)
        }
    }

    override fun removeCallbacks(r: Runnable) {
        synchronized(pending) { pending.remove(r)?.forEach { it.cancel(false) } }
    }
}

/** The same contract on one plain thread, for the CLI, which has no UI thread. */
class ExecutorMainThread(name: String = "relaypony-main") : MainThread {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    private val pending = HashMap<Runnable, MutableList<ScheduledFuture<*>>>()

    override fun post(r: Runnable) { exec.execute(r) }

    override fun postDelayed(r: Runnable, delayMs: Long) {
        synchronized(pending) {
            lateinit var f: ScheduledFuture<*>
            f = exec.schedule({
                val live = synchronized(pending) { pending[r]?.remove(f) == true }
                if (live) r.run()
            }, delayMs, TimeUnit.MILLISECONDS)
            pending.getOrPut(r) { ArrayList() }.add(f)
        }
    }

    override fun removeCallbacks(r: Runnable) {
        synchronized(pending) { pending.remove(r)?.forEach { it.cancel(false) } }
    }

    fun shutdown() = exec.shutdownNow()
}
