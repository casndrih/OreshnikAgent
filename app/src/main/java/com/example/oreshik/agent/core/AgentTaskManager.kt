package com.example.oreshik.agent.core

import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Task lifecycle for agent operations.
 *
 * Each operation (chat request, wake-word request) gets its own task identity,
 * status, cooperative cancellation handle, and conversation context reference.
 * States prevent duplicate execution, stale responses, conflicting UI updates,
 * and accidental cancellation of unrelated tasks.
 *
 * This is deliberately small and built on the existing coroutine architecture —
 * no separate task-management framework.
 */
object AgentTaskManager {

    private const val TAG = "TaskManager"

    enum class State { QUEUED, RUNNING, WAITING_FOR_USER_INPUT, PAUSED, CANCELLING, COMPLETED, FAILED, CANCELLED }

    data class Task(
        val id: String,
        @Volatile var state: State = State.QUEUED,
        /** Coroutine job for cooperative cancellation (null when queued). */
        @Volatile var job: Job? = null,
        /** Session this task belongs to (task ↔ session kept independent but linked). */
        val sessionId: String,
        val model: String = "",
        val startedAt: Long = System.currentTimeMillis(),
    )

    private val tasks = ConcurrentHashMap<String, Task>()
    private val mutex = Mutex()

    fun create(sessionId: String, model: String = ""): Task {
        val id = "task_${System.currentTimeMillis()}_${(0..999).random()}"
        val t = Task(id = id, sessionId = sessionId, model = model, state = State.QUEUED)
        tasks[id] = t
        AppLogger.d(TAG, "Task created: $id (session=$sessionId model=$model)")
        return t
    }

    fun get(id: String): Task? = tasks[id]

    suspend fun markRunning(task: Task, job: Job) = mutex.withLock {
        when (task.state) {
            State.QUEUED -> {
                task.job = job
                task.state = State.RUNNING
                AppLogger.d(TAG, "Task running: ${task.id}")
            }
            State.RUNNING -> task.job = job
            State.CANCELLING -> {
                task.job = job
                // A cancellation may arrive while the task is still queued.
                // Attach and cancel the job without reviving the task as RUNNING.
                job.cancel()
            }
            else -> job.cancel()
        }
    }

    /** Reserved for interactive flows that park a run on user input. */
    @Suppress("unused")
    suspend fun markWaitingForUserInput(task: Task) = mutex.withLock {
        if (task.state == State.RUNNING) task.state = State.WAITING_FOR_USER_INPUT
    }

    /** Reserved for pause/resume flows. */
    @Suppress("unused")
    suspend fun markPaused(task: Task) = mutex.withLock {
        if ((task.state == State.RUNNING) || (task.state == State.WAITING_FOR_USER_INPUT)) {
            task.state = State.PAUSED
        }
    }

    suspend fun markTerminal(task: Task, state: State) = mutex.withLock {
        require(
            (state == State.COMPLETED) || (state == State.FAILED) || (state == State.CANCELLED),
        )
        task.state = state
        task.job = null
        AppLogger.i(TAG, "Task ${task.id} → $state")
        // Bounded memory: drop settled tasks after a grace period so history stays queryable briefly.
        cleanup()
    }

    /**
     * Requests cooperative cancellation of [taskId]. Only affects that task —
     * unrelated sessions/tasks are never cancelled. Returns false when the task
     * doesn't exist or already settled.
     */
    suspend fun cancel(taskId: String): Boolean = mutex.withLock {
        val t = tasks[taskId] ?: return@withLock false
        when (t.state) {
            State.QUEUED, State.RUNNING, State.WAITING_FOR_USER_INPUT, State.PAUSED -> {
                t.state = State.CANCELLING
                t.job?.cancel()
                AppLogger.i(TAG, "Task cancelled: $taskId")
                true
            }
            else -> false
        }
    }

    /**
     * Checks if a task is cancelled (state is CANCELLING or CANCELLED).
     * Useful for callers to early-exit from long-running operations.
     */
    @Suppress("unused")
    suspend fun isCancelled(taskId: String): Boolean = mutex.withLock {
        val task = tasks[taskId]
        (task != null) && ((task.state == State.CANCELLING) || (task.state == State.CANCELLED))
    }

    /**
     * Checks if any task for a session is cancelled.
     */
    @Suppress("unused")
    suspend fun isSessionCancelled(sessionId: String): Boolean = mutex.withLock {
        tasks.values.any { it.belongsTo(sessionId) && it.isCancelling() }
    }

    private fun Task.belongsTo(session: String): Boolean = sessionId == session

    private fun Task.isCancelling(): Boolean =
        (state == State.CANCELLING) || (state == State.CANCELLED)

    fun isActive(sessionId: String): Boolean = tasks.values.any { task ->
        (task.sessionId == sessionId) && task.isPending()
    }

    private fun Task.isPending(): Boolean =
        (state == State.QUEUED) || (state == State.RUNNING) ||
            (state == State.WAITING_FOR_USER_INPUT) || (state == State.PAUSED) || (state == State.CANCELLING)

    fun activeCount(): Int = tasks.values.count { task -> task.isPending() }

    /** Bounded cleanup: removes settled tasks older than 5 minutes. */
    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - (5 * 60_000L)
        tasks.entries.removeIf { (_, task) ->
            (task.job == null) && task.isSettled() && (task.startedAt < cutoff)
        }
    }

    private fun Task.isSettled(): Boolean =
        (state == State.COMPLETED) || (state == State.FAILED) || (state == State.CANCELLED)
}
