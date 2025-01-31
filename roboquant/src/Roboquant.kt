/*
 * Copyright 2021 Neural Layer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.roboquant

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import org.roboquant.brokers.Account
import org.roboquant.brokers.Broker
import org.roboquant.brokers.sim.SimBroker
import org.roboquant.common.Background
import org.roboquant.common.Logging
import org.roboquant.common.Summary
import org.roboquant.common.Timeframe
import org.roboquant.feeds.Event
import org.roboquant.feeds.EventChannel
import org.roboquant.feeds.Feed
import org.roboquant.logging.MemoryLogger
import org.roboquant.logging.MetricsLogger
import org.roboquant.metrics.Metric
import org.roboquant.orders.Order
import org.roboquant.policies.DefaultPolicy
import org.roboquant.policies.Policy
import org.roboquant.strategies.Strategy
import java.time.Duration
import java.time.Instant
import java.util.logging.Level

/**
 * Roboquant is the engine of the platform that ties [strategy], [policy] and [broker] together and caters to a wide
 * variety of testing and live trading scenarios. Through [metrics] and a [logger] it provides insights into the
 * performance of a [run].
 *
 */
class Roboquant(
    val strategy: Strategy,
    vararg val metrics: Metric,
    val policy: Policy = DefaultPolicy(),
    val broker: Broker = SimBroker(),
    val logger: MetricsLogger = MemoryLogger(),
    private val channelCapacity: Int = 100,
) {

    private var runCounter = 0
    private val kotlinLogger = Logging.getLogger(Roboquant::class)
    private val components = listOf(strategy, policy, broker, logger) + metrics

    init {
        kotlinLogger.fine { "Created new roboquant instance with name" }
    }

    /**
     * Run and evaluate the underlying performance of the strategy and policy. You don't
     * invoke this method directly but rather use the [run] method instead.
     *
     * Under the hood this method replies on the [step] method to take a single step.
     */
    private suspend fun runPhase(feed: Feed, runInfo: RunInfo) {

        if (!feed.timeframe.overlap(runInfo.timeframe)) return
        runInfo.timeframe = runInfo.timeframe.intersect(feed.timeframe)

        val channel = EventChannel(channelCapacity, runInfo.timeframe)
        val job = Background.ioJob {
            try {
                feed.play(channel)
            } finally {
                channel.close()
            }
        }

        start(runInfo.phase)
        try {
            var orders = listOf<Order>()
            while (true) {
                val event = channel.receive()
                orders = step(orders, event, runInfo)
            }
        } catch (_: ClosedReceiveChannelException) {
            // intentionally empty
        } finally {
            end(runInfo.phase)
            if (job.isActive) job.cancel()
            channel.close()
        }
    }

    /**
     * Inform components of the start of a phase, this provides them with the opportunity to reset state and
     * re-initialize values if required.
     *
     * @param runPhase
     */
    private fun start(runPhase: RunPhase) {
        for (component in components) component.start(runPhase)
    }

    /**
     * Inform components of the end of a phase, this provides them with the opportunity to release resources
     * if required or process aggregated results.
     *
     * @param runPhase
     */
    private fun end(runPhase: RunPhase) {
        for (component in components) component.end(runPhase)
    }

    /**
     * Reset all state including that of the used components. This allows to start a fresh run with the same
     * configuration as the original instance.
     */
    fun reset() {
        for (component in components) component.reset()
        runCounter = 0
    }

    /**
     * Start a new run using the provided [feed] as data. If no [timeframe] is provided all the events in the feed
     * will be used. Optionally you can provide a [validation] timeframe that will trigger a separate validation phase.
     * You can also repeat the run for a number of [episodes].
     *
     * You can provide a custom [runName] that will help to later identify this run. If none is provided, a name will
     * be generated with the format "run-<counter>"
     *
     *  The following provides a schematic overview of the flow of a run:
     *
     * [Feed] -> [Strategy] -> [Policy] -> [Broker] -> [Metric] -> [MetricsLogger]
     *
     * This is the synchronous (blocking) method of run that is convenient to use. However, if you want to execute runs
     * in parallel have also a look at [runAsync]
     */
    fun run(
        feed: Feed,
        timeframe: Timeframe = Timeframe.INFINITE,
        validation: Timeframe? = null,
        runName: String? = null,
        episodes: Int = 1
    ) =
        runBlocking {
            runAsync(feed, timeframe, validation, runName, episodes)
        }

    /**
     * This is exactly the same method as the [run] method but as the name already suggest, asynchronously.
     *
     * @see [run]
     */
    suspend fun runAsync(
        feed: Feed,
        timeframe: Timeframe = Timeframe.INFINITE,
        validation: Timeframe? = null,
        runName: String? = null,
        episodes: Int = 1
    ) {
        require(episodes > 0) { "episodes need to be greater than zero" }
        val run = runName ?: "run-${runCounter++}"
        val runInfo = RunInfo(run)
        kotlinLogger.fine { "Starting run $runInfo for $episodes episodes" }

        repeat(episodes) {
            runInfo.episode++
            runInfo.phase = RunPhase.MAIN
            runInfo.timeframe = timeframe
            runPhase(feed, runInfo)
            if (validation !== null) {
                runInfo.timeframe = validation
                runInfo.phase = RunPhase.VALIDATE
                runPhase(feed, runInfo)
            }
        }
        kotlinLogger.fine { "Finished run $runInfo" }
    }


    /**
     * Take a single step in the timeline. The broker is always invoked before the strategy and policy to ensure it is
     * impossible to look ahead in the future. So the loop really is:
     *
     *  feed --|event|--> broker --|account|--> metrics -> strategy --|signals|--> policy --|orders|-->
     *
     */
    private fun step(orders: List<Order>, event: Event, runInfo: RunInfo): List<Order> {
        runInfo.step++
        runInfo.time = event.time

        val account = broker.place(orders, event)
        runMetrics(account, event, runInfo)
        val signals = strategy.generate(event)
        return policy.act(signals, account, event)
    }

    /**
     * Calculate the configured [metrics] and log the results. This includes also metrics that are recorded by the
     * strategy, policy and broker.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runMetrics(account: Account, event: Event, runInfo: RunInfo) {
        val info = runInfo.copy()
        for (metric in metrics) {
            try {
                metric.calculate(account, event)
            } catch (e: Throwable) {
                kotlinLogger.log(Level.WARNING, "failed when calculate metric ${metric::class.simpleName}", e)
            }
        }

        for (component in components) {
            val metrics = component.getMetrics()
            logger.log(metrics, info)
        }

    }

    /**
     * Provide a short summary of the state of this roboquant.
     */
    fun summary(): Summary {
        val s = Summary("roboquant")
        s.add("strategy", strategy::class.simpleName)
        s.add("policy", policy::class.simpleName)
        s.add("logger", logger::class.simpleName)
        s.add("metrics", metrics.size)
        return s
    }

}


/**
 * Run related info provided to metrics loggers together with the metric results.
 *
 * @property run the name of the run
 * @property episode the episode number
 * @property step the step
 * @property time the time
 * @property timeframe the total timeframe of the run, if not known it will be [Timeframe.INFINITE]
 * @property phase the phase of the run
 * @constructor Create new RunInfo object
 */
data class RunInfo internal constructor(
    val run: String,
    var episode: Int = 0,
    var step: Int = 0,
    var time: Instant = Instant.MIN,
    var timeframe: Timeframe = Timeframe.INFINITE,
    var phase: RunPhase = RunPhase.MAIN
) {

    /**
     * What is the duration of the run so far
     */
    val duration: Duration
        get() = Duration.between(timeframe.start, time)


}

/**
 * Enumeration of fhe different phases that a run can be in, [MAIN] and [VALIDATE]. Especially with self learning
 * strategies, it is important that you evaluate your strategy on yet unseen data, so you don't over-fit.
 *
 * See also [Roboquant.run] how to run your strategy with different phases enabled.
 *
 * @property value String value of the phase
 */
enum class RunPhase(val value: String) {

    /**
     * Main run phase
     */
    MAIN("MAIN"),

    /**
     * Validation run phase
     */
    VALIDATE("VALIDATE"),
}
