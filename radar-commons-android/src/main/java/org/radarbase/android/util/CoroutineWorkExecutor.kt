package org.radarbase.android.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Coroutine-based executor that manages and schedules tasks with various options for delayed,
 * repeated, and safe execution. Supports exception handling, task cancellation, and synchronization.
 *
 * @param name The name assigned to this executor.
 * @param coroutineContext The coroutine context to use for execution.
 */
class CoroutineWorkExecutor(
    val name: String,
    coroutineContext: CoroutineContext
) {

    private val executorExceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, ex ->
            logger.error("Failed to run task", ex)
        }
    private val job: Job = SupervisorJob()
    private val executorScope: CoroutineScope =
        CoroutineScope(job + coroutineContext + CoroutineName(name) + executorExceptionHandler)

    private val nullValue: Any = Any()

    private val activeTasks = mutableListOf<CoroutineFutureExecutor>()

    /**
     * Executes a suspendable task and waits for its completion.
     *
     * @param work The suspendable task to be executed.
     * @throws ExecutionException if the task fails during execution.
     */
    @Throws(ExecutionException::class)
    suspend fun waitAndExecute(work: suspend () -> Unit): Unit = computeResult(work)!!

    /**
     * Executes a suspendable task and returns its result, or throws an exception if it fails.
     *
     * @param work The suspendable task to be executed.
     * @param T The return type of the task.
     * @return The result of the task, or null if the task returns null.
     * @throws ExecutionException if the task fails during execution.
     */
    @Throws(ExecutionException::class)
    suspend fun <T> computeResult(work: suspend () -> T?): T? = suspendCoroutine { continuation ->
        executorScope.launch {
            try {
                val result = work() ?: nullValue
                if (result == nullValue) {
                    continuation.resume(null)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as T)
                }
            } catch (ex: Exception) {
                continuation.resumeWithException(ExecutionException(ex))
            }
        }
    }

    /**
     * Launches a suspendable task, optionally on a specified [CoroutineDispatcher].
     *
     * @param task The suspendable task to be executed.
     * @param defaultToCurrentDispatcher If true, uses the current dispatcher if no dispatcher is specified.
     * @param dispatcher The dispatcher to use for task execution, or null to use the executor's dispatcher.
     */
    @OptIn(ExperimentalStdlibApi::class)
    suspend fun execute(
        task: suspend () -> Unit,
        defaultToCurrentDispatcher: Boolean = false,
        dispatcher: CoroutineDispatcher? = null
    ) {
        executorScope.launch {
            if (
                (dispatcher == null) ||
                defaultToCurrentDispatcher ||
                (this.coroutineContext[CoroutineDispatcher] == dispatcher)
            ) {
                runTaskSafely(task)
            } else {
                withContext(dispatcher) {
                    runTaskSafely(task)
                }
            }
        }
    }

    /**
     * Schedules a task to be executed after a specified delay.
     *
     * @param delayMillis Delay in milliseconds before executing the task.
     * @param task The task to execute after the delay.
     * @return A [CoroutineFutureExecutor] to manage the delayed task future.
     */
    fun delay(
        delayMillis: Long,
        task: suspend () -> Unit
    ): CoroutineFutureExecutor {
        val future = CoroutineFutureExecutorRef()
        activeTasks.add(future)
        future.startDelayed(delayMillis, task)
        return future
    }

    /**
     * Repeatedly executes a task with a specified delay between each execution.
     *
     * @param delayMillis Delay in milliseconds between each execution of the task.
     * @param task The task to execute repeatedly.
     * @return A [CoroutineFutureExecutor] to manage the repeated task.
     */
    fun repeat(
        delayMillis: Long,
        task: suspend () -> Unit
    ): CoroutineFutureExecutor {
        val future = CoroutineFutureExecutorRef()
        activeTasks.add(future)
        future.startRepeated(delayMillis, task)
        return future
    }

    /**
     * Cancels all scheduled and active tasks.
     */
    private fun cancelAllFutures() {
        activeTasks.forEach { it.cancel() }
        activeTasks.clear()
    }

    /**
     * Safely runs a task, logging errors if execution fails.
     *
     * @param task The suspendable task to be executed.
     */
    private suspend fun runTaskSafely(task: suspend () -> Unit) {
        val executionResult = runCatching {
            task()
        }
        if (executionResult.isFailure) {
            logger.error("Failed to execute task: {}", executionResult.toString())
        }
    }

    /**
     * Interface for managing a task handle with controls for execution and cancellation.
     */
    interface CoroutineFutureExecutor {

        fun startDelayed(delayMillis: Long, task: suspend () -> Unit)

        fun startRepeated(delayMillis: Long, task: suspend () -> Unit)

        fun startRepeatedUntilFalse(
            delayMillis: Long,
            task: suspend () -> Boolean
        )

        fun cancel()
    }

    interface CoroutineFutureHandle {
        suspend fun awaitNow()

        suspend fun runNow()

        fun cancel()
    }

    /**
     * Internal class for handling task execution and cancellation with mutex support.
     */
    inner class CoroutineFutureHandleRef(
        private var job: Job?,
        private val task: suspend () -> Unit
    ) : CoroutineFutureHandle {

        private val handleMutex: Mutex = Mutex()

        override suspend fun awaitNow() {
            handleMutex.withLock {
                job?.cancel()
                waitAndExecute(task)
            }
        }

        override suspend fun runNow() {
            handleMutex.withLock {
                job?.cancel()
                execute(task)
            }
        }

        @Synchronized
        override fun cancel() {
            job = job?.let {
                it.cancel()
                null
            }
        }
    }

    /**
     * Internal class representing a scheduled task executor with options for delay and repetition.
     */
    inner class CoroutineFutureExecutorRef : CoroutineFutureExecutor {

        var job: Job? = null

        override fun startDelayed(delayMillis: Long, task: suspend () -> Unit) {
            checkNullJob() ?: return
            job = executorScope.launch {
                delay(delayMillis)
                task()
            }
        }

        override fun startRepeated(delayMillis: Long, task: suspend () -> Unit) {
            checkNullJob() ?: return
            job = executorScope.launch {
                while (isActive) {
                    delay(delayMillis)
                    task()
                }
            }
        }

        override fun startRepeatedUntilFalse(
            delayMillis: Long,
            task: suspend () -> Boolean
        ) {
            checkNullJob() ?: return
            job = executorScope.launch {
                var lastResult = true
                while (isActive && lastResult) {
                    delay(delayMillis)
                    lastResult = task()
                }
            }
        }

        private fun checkNullJob(): Unit? {
            if (job != null) {
                logger.warn("Tried re-assigning the already assigned job")
                return null
            }
            return Unit
        }

        override fun cancel() {
            job = job?.let {
                it.cancel()
                null
            }
        }
    }

    fun stop() {
        cancelAllFutures()
        job.cancel()
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(CoroutineWorkExecutor::class.java)

        private val instances = mutableMapOf<String, CoroutineWorkExecutor>()

        /**
         * Retrieves or creates an instance of [CoroutineWorkExecutor] by name.
         *
         * @param name The unique name for the executor.
         * @param coroutineContext The coroutine context to be used by this executor.
         * @return A singleton instance of [CoroutineWorkExecutor] for the specified name.
         */
        @Synchronized
        fun getInstance(name: String, coroutineContext: CoroutineContext): CoroutineWorkExecutor {
            return instances[name] ?: CoroutineWorkExecutor(
                name,
                coroutineContext
            ).also { instances[name] = it }
        }

    }
}