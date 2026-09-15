package ai.rever.boss.plugin.dynamic.fluckbrowser

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PasswordCopyFeedbackTest {
    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Harness(private val scope: TestScope) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val job = scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        private val composition = Composition(NoOpApplier(), recomposer)
        val password = mutableStateOf("first-password")
        lateinit var feedback: PasswordCopyFeedback

        init {
            composition.setContent { feedback = rememberPasswordCopyFeedback(password.value) }
            flush()
        }

        fun flush() {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            clock.sendFrame(scope.testScheduler.currentTime * 1_000_000)
            scope.runCurrent()
        }

        fun advance(ms: Long) {
            scope.advanceTimeBy(ms)
            flush()
        }

        fun close() {
            composition.dispose()
            recomposer.cancel()
            job.cancel()
        }
    }

    @Test
    fun `regeneration clears copied feedback and old timer cannot clear a new attempt`() = runTest {
        val ui = Harness(this)
        try {
            ui.feedback.report(true)
            ui.flush()
            assertEquals(PasswordCopyState.COPIED, ui.feedback.state)
            ui.advance(1000)
            ui.password.value = "regenerated-password"
            ui.flush()
            assertEquals(PasswordCopyState.IDLE, ui.feedback.state)
            ui.feedback.report(false)
            ui.flush()
            ui.advance(600)
            assertEquals(PasswordCopyState.FAILED, ui.feedback.state)
            ui.advance(3400)
            assertEquals(PasswordCopyState.IDLE, ui.feedback.state)
        } finally {
            ui.close()
        }
    }

    @Test
    fun `repeated outcomes restart both success and failure feedback durations`() = runTest {
        val ui = Harness(this)
        try {
            for ((copied, duration) in listOf(true to 1600L, false to 4000L)) {
                val expected = if (copied) PasswordCopyState.COPIED else PasswordCopyState.FAILED
                ui.feedback.report(copied)
                ui.flush()
                ui.advance(duration - 100)
                ui.feedback.report(copied)
                ui.flush()
                ui.advance(100)
                assertEquals(expected, ui.feedback.state, "earlier attempt must not expire the latest feedback")
                ui.advance(duration - 101)
                assertEquals(expected, ui.feedback.state)
                ui.advance(1)
                assertEquals(PasswordCopyState.IDLE, ui.feedback.state)
            }
        } finally {
            ui.close()
        }
    }
}
