/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.android.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Coroutine-based executor that manages and schedules tasks with various options for delayed,
 * repeated, and safe execution. Supports exception handling, task cancellation, and synchronization.
 *
 * @param job The job assigned to this executor.
 * @param coroutineDispatcher The coroutine dispatcher to use for execution.
 */
class CoroutineTaskExecutor(
    private val invokingClassName: String,
    private val coroutineDispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null
    private val executorExceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, ex ->
            logger.error("Failed to run task", ex)
            throw ex
        }

    private var executorScope: CoroutineScope? = null

    private val nullValue: Any = Any()
    val isStarted: AtomicBoolean = AtomicBoolean(executorScope != null)
    private val executorMutex: Mutex = Mutex()

    private val activeTasks = mutableListOf<CoroutineFutureExecutor>()

    fun start(job: Job = Job())  {
        if (isStarted.get()) {
            logger.warn("Tried to start executor multiple times.")
            return
        }
        this.job = job
        executorScope = CoroutineScope(coroutineDispatcher + job + executorExceptionHandler)
        isStarted.set(true)
    }
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
        val activeStatus: Boolean? = executorScope?.isActive
        if (activeStatus == null || activeStatus == false) {
            logger.warn("Scope is already cancelled")
            continuation.resume(null)
            return@suspendCoroutine
        }
        checkExecutorStarted() ?: return@suspendCoroutine
        executorScope?.launch {
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

    fun executeReentrant(task: suspend () -> Unit) = execute(task)

    fun execute(task: suspend () -> Unit) {
        val activeStatus: Boolean? = executorScope?.isActive
        if (activeStatus == null || activeStatus == false) {
            logger.warn("Can't execute task, scope is already cancelled")
            return
        }
        checkExecutorStarted() ?: return

        executorScope?.launch {
            executorMutex.withLock {
                runTaskSafely(task)
            }
        }
    }

    fun returnJobAndExecute(task: suspend () -> Unit): Job? {
        val activeStatus: Boolean? = executorScope?.isActive
        if (activeStatus == null || activeStatus == false) {
            logger.warn("Can't execute task and return job, scope is already cancelled")
            return null
        }
        checkExecutorStarted() ?: return null
        return executorScope?.launch {
            runTaskSafely(task)
        }
    }

//    /**
//     * Launches a suspendable task, optionally on a specified [CoroutineDispatcher].
//     *
//     * @param task The suspendable task to be executed.
//     * @param defaultToCurrentDispatcher If true, uses the current dispatcher if no dispatcher is specified.
//     * @param dispatcher The dispatcher to use for task execution, or null to use the executor's dispatcher.
//     */
//    @OptIn(ExperimentalStdlibApi::class)
//    fun execute(
//        task: suspend () -> Unit,
//        defaultToCurrentDispatcher: Boolean = false,
//        dispatcher: CoroutineDispatcher? = null
//    ) {
//        if (!executorScope?.isActive) {
//            logger.warn("Can't execute task, scope is already cancelled")
//            return
//        }
//        checkExecutorStarted() ?: return
//        executorScope?.launch {
//            if (
//                (dispatcher == null) ||
//                defaultToCurrentDispatcher ||
//                (this.coroutineContext[CoroutineDispatcher] == dispatcher)
//            ) {
//                executorMutex.withLock {
//                    runTaskSafely(task)
//                }
//            } else {
//                withContext(dispatcher) {
//                    executorMutex.withLock {
//                        runTaskSafely(task)
//                    }
//                }
//            }
//        }
//    }

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
    ): CoroutineFutureHandle? {
        checkExecutorStarted() ?: return null
        val future = CoroutineFutureExecutorRef()
        activeTasks.add(future)
        future.startDelayed(delayMillis, task)
        return CoroutineFutureHandleRef(future.job, task)
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
    ): CoroutineFutureHandle? {
        checkExecutorStarted() ?: return null
        val future = CoroutineFutureExecutorRef()
        activeTasks.add(future)
        future.startRepeated(delayMillis, task)
        return CoroutineFutureHandleRef(future.job, task)
    }

    /**
     * Cancels all scheduled and active tasks.
     */
    private fun cancelAllFutures() {
        activeTasks.forEach { it.cancel() }
        activeTasks.clear()
    }

    /**
     * Cancel the coroutine, running [finalization], if any, as the last operation.
     * None of the finalization will run if [CoroutineTaskExecutor.start] was not called.
     */
    suspend fun stop(finalization: (suspend () -> Unit)? = null) = coroutineScope {
        finalization?.let {
            executorScope?.also { execScope ->
                checkExecutorStarted() ?: return@let
                joinAll(
                    execScope.launch {
                        runTaskSafely(it)
                        logger.info("Executed the finalization task")
                    }
                )
            } ?: launch {
                runTaskSafely(it)
            }
        }
        cancelAllFutures()
        job?.cancel()
        isStarted.set(false)
        job = null
        executorScope = null
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
            job?.also {
                it.cancel()
                job = null
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
            job = executorScope?.launch {
                delay(delayMillis)
                runTaskSafely(task)
            }
        }

        override fun startRepeated(delayMillis: Long, task: suspend () -> Unit) {
            checkNullJob() ?: return
            job = executorScope?.launch {
                while (isActive) {
                    delay(delayMillis)
                    runTaskSafely(task)
                }
            }
        }

        override fun startRepeatedUntilFalse(
            delayMillis: Long,
            task: suspend () -> Boolean
        ) {
            checkNullJob() ?: return
            job = executorScope?.launch {
                var lastResult = true
                while (isActive && lastResult) {
                    delay(delayMillis)
                    lastResult = try {
                        task()
                    } catch (ex: Exception) {
                        false
                    }
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
            job?.also {
                it.cancel()
                job = null
            }
        }
    }

    private fun checkExecutorStarted(): Boolean? {
        if (!isStarted.get()) {
            logger.warn("Either executor not started yet or it has already stopped! Please call start() to execute tasks")
        }
        return if (isStarted.get()) return true else null
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(CoroutineTaskExecutor::class.java)
    }
}