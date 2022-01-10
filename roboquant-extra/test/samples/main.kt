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

@file:Suppress("KotlinConstantConditions")

package org.roboquant.samples

import org.roboquant.Roboquant
import org.roboquant.alpaca.AlpacaBroker
import org.roboquant.alpaca.AlpacaHistoricFeed
import org.roboquant.alpaca.AlpacaLiveFeed
import org.roboquant.alpaca.AlpacaPeriod
import org.roboquant.brokers.FeedExchangeRates
import org.roboquant.brokers.FixedExchangeRates
import org.roboquant.brokers.Portfolio
import org.roboquant.common.*
import org.roboquant.feeds.Event
import org.roboquant.feeds.OrderBook
import org.roboquant.feeds.avro.AvroUtil
import org.roboquant.feeds.csv.CSVConfig
import org.roboquant.feeds.csv.CSVFeed
import org.roboquant.feeds.filter
import org.roboquant.iex.Range
import org.roboquant.logging.MemoryLogger
import org.roboquant.metrics.AccountSummary
import org.roboquant.metrics.OpenPositions
import org.roboquant.metrics.ProgressMetric
import org.roboquant.oanda.*
import org.roboquant.orders.FOK
import org.roboquant.orders.MarketOrder
import org.roboquant.policies.DefaultPolicy
import org.roboquant.strategies.EMACrossover
import org.roboquant.yahoo.YahooHistoricFeed
import java.util.logging.Level


fun alpacaBroker() {
    val feed = CSVFeed("data/US", CSVConfig(priceAdjust = true))
    val broker = AlpacaBroker()
    val strategy = EMACrossover.midTerm()
    val roboquant = Roboquant(strategy, AccountSummary(), broker = broker)
    roboquant.run(feed)
    broker.account.summary().print()
}


fun allAlpaca() {
    val broker = AlpacaBroker()
    val account = broker.account
    account.summary().log()
    account.orders.summary().log()
    account.portfolio.summary().log()

    val feed = AlpacaLiveFeed()
    feed.heartbeatInterval = 1000


    // Let trade all the assets that start with an A or are in our portfolio already
    // val assets = feed.availableAssets.filter { it.symbol.startsWith("AA") } + account.portfolio.assets
    val assets = account.portfolio.assets
    feed.subscribe(assets.distinct())

    val strategy = EMACrossover(3, 5)
    val roboquant = Roboquant(strategy, AccountSummary(), broker = broker)
    val tf = TimeFrame.next(15.minutes)
    roboquant.run(feed, tf)
    feed.disconnect()

    roboquant.broker.account.summary().log()
    roboquant.logger.summary(3).log()
}


fun alpacaHistoricFeed() {
    val feed = AlpacaHistoricFeed()
    val tf = TimeFrame.past(100.days) - 15.minutes
    feed.retrieve("AAPL", "IBM", timeFrame = tf, period = AlpacaPeriod.DAY)
    val strategy = EMACrossover.midTerm()
    val roboquant = Roboquant(strategy, AccountSummary(), ProgressMetric())
    roboquant.run(feed)
    roboquant.broker.account.summary().log()
    roboquant.logger.summary().log()
}

fun alpacaFeed() {
    // Logging.setLevel(Level.FINER)
    val feed = AlpacaLiveFeed()
    val assets = feed.assets.take(50)
    feed.subscribe(assets)
    feed.heartbeatInterval = 1000
    val strategy = EMACrossover.midTerm()
    val roboquant = Roboquant(strategy, AccountSummary(), ProgressMetric())
    val tf = TimeFrame.next(5.minutes)
    roboquant.run(feed, tf)
    feed.disconnect()

    roboquant.broker.account.summary().log()
    roboquant.logger.summary().log()

}

fun feedIEX() {
    val token = Config.getProperty("IEX_PUBLIC_KEY") ?: throw Exception("No token found")
    val feed = org.roboquant.iex.IEXHistoricFeed(token)
    feed.retrievePriceBar("AAPL", "GOOGL", "FB", range = Range.TWO_YEARS)

    val strategy = EMACrossover(10, 30)
    val roboquant = Roboquant(strategy, AccountSummary())
    roboquant.run(feed)
    roboquant.broker.account.summary().log()
}


fun feedIEXLive() {
    val feed = org.roboquant.iex.IEXLiveFeed()
    val apple = Asset("AAPL")
    val google = Asset("GOOG")
    feed.subscribeTrades(apple, google)

    val strategy = EMACrossover()
    val roboquant = Roboquant(strategy, AccountSummary(), ProgressMetric())
    roboquant.run(feed, TimeFrame.next(5.minutes))
    roboquant.broker.account.summary().log()
    roboquant.logger.summary().log()
}


fun feedYahoo() {
    val feed = YahooHistoricFeed()
    val apple = Asset("AAPL")
    val google = Asset("GOOG")
    val last300Days = TimeFrame.past(300.days)
    feed.retrieve(apple, google, timeFrame = last300Days)

    val strategy = EMACrossover()
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, AccountSummary(), OpenPositions(), logger = logger)
    roboquant.run(feed)
    logger.summary(10)
}


fun oanda() {
    val feed = OANDAHistoricFeed()
    feed.retrieveCandles("EUR_USD", "USD_JPY", "GBP_USD")
    feed.assets.summary().log()
    println(feed.timeline.size)
    println(feed.timeFrame)

}



fun oandaLong() {
    val feed = OANDAHistoricFeed()
    val timeFrame = TimeFrame.parse("2020-01-01", "2020-02-01")

    // There is a limit on what we can download per API call, so we split it in individual days
    for (tf in timeFrame.split(1.days)) {
        feed.retrieveCandles("EUR_USD", "USD_JPY", "GBP_USD", timeFrame = tf)
    }
    feed.assets.summary().log()
    println(feed.timeline.size)
    println(feed.timeFrame)

    // Now we store it in a local Avro file
    AvroUtil.record(feed, "/Users/peter/data/avro/forex.avro")

}


fun oanda2() {
    Currency.increaseDigits(3)
    val feed = OANDAHistoricFeed()
    feed.retrieveCandles("EUR_USD", "EUR_GBP", "GBP_USD")
    Config.exchangeRates = FeedExchangeRates(feed)

    val roboquant = OANDA.roboquant(EMACrossover())
    roboquant.run(feed)
    roboquant.broker.account.fullSummary().print()
}


fun oandaLive() {
    val feed = OANDALiveFeed()
    feed.subscribeOrderBook("EUR_USD", "USD_JPY", "GBP_USD")
    val tf = TimeFrame.next(5.minutes)
    val actions = feed.filter<OrderBook>(tf)
    println(actions.size)
}



fun oandaPaperTrading() {
    val broker = OANDABroker(enableOrders = true)
    Config.exchangeRates = OANDAExchangeRates(broker.availableAssets)

    val feed = OANDALiveFeed()
    val assets = broker.availableAssets.findByCurrencies("EUR", "USD", "JPY", "GBP")
    feed.subscribeOrderBook(assets)

    val roboquant = Roboquant(EMACrossover.shortTerm(), broker = broker)

    val tf = TimeFrame.next(10.minutes)
    roboquant.run(feed, tf)
    Currency.increaseDigits(3) // We want to use some extra digits
    roboquant.broker.account.fullSummary().print()
}


fun oandaPaperTradingClosePositions() {
    val broker = OANDABroker(enableOrders = true)
    Logging.setLevel(Level.FINE)
    Config.exchangeRates = OANDAExchangeRates(broker.availableAssets)
    val orders = broker.account.portfolio.diff(Portfolio()).map { MarketOrder(it.key, it.value) }
    broker.place(orders, Event.empty())
    broker.account.fullSummary().print()
}



fun oandaLiveRecord() {
    val feed = OANDALiveFeed()
    feed.subscribeOrderBook("EUR_USD", "USD_JPY", "GBP_USD")
    val tf = TimeFrame.next(5.minutes)
    AvroUtil.record(feed, "/Users/peter/tmp/oanda.avro", tf)
}


fun oandaLivePrices() {
    Logging.setLevel(Level.FINE, "org.roboquant")
    val feed = OANDALiveFeed()
    feed.subscribePrices("EUR_USD", "USD_JPY", "GBP_USD")
    val data = feed.filter<OrderBook>(TimeFrame.next(1.minutes))
    data.summary().log()
}


fun oandaBroker() {
    Config.exchangeRates = FixedExchangeRates(Currency.EUR, Currency.USD to 0.9, Currency.GBP to 1.2)
    val broker = OANDABroker()
    broker.account.summary().log()
    broker.account.portfolio.summary().log()
    broker.availableAssets.summary().log()

    val strategy = EMACrossover()
    val roboquant = Roboquant(strategy, AccountSummary(), broker = broker)

    val feed = OANDALiveFeed()
    feed.subscribeOrderBook("EUR_USD", "GBP_USD", "GBP_EUR")
    val twoMinutes = TimeFrame.next(5.minutes)
    roboquant.run(feed, twoMinutes)
    broker.account.portfolio.summary().log()
}


fun oandaBroker3() {
    Logging.setLevel(Level.FINE, "OANDABroker")
    Currency.increaseDigits(3)

    val broker = OANDABroker(enableOrders = true)
    val account = broker.account
    account.fullSummary().print()
    val feed = OANDALiveFeed()
    feed.subscribeOrderBook("GBP_USD", "EUR_USD", "EUR_GBP")
    feed.heartbeatInterval = 30_000L

    val strategy = EMACrossover() // Use EMA Crossover strategy
    val policy = DefaultPolicy(shorting = true) // We want to short if we do Forex trading
    val roboquant = Roboquant(strategy, AccountSummary(), policy = policy, broker = broker)
    val timeFrame = TimeFrame.next(1.minutes) // restrict the time from now for the next minutes
    roboquant.run(feed, timeFrame)
    account.fullSummary().print()
}

fun oandaBroker2(createOrder: Boolean = true) {
    Logging.setLevel(Level.FINE, "OANDABroker")
    val broker = OANDABroker(enableOrders = true)
    broker.account.fullSummary().log()
    broker.availableAssets.summary().log()

    if (createOrder) {
        val asset = broker.availableAssets.findBySymbols("EUR_USD").first()
        val order = MarketOrder(asset, -100.0, tif = FOK())
        broker.place(listOf(order), Event.empty())
        broker.account.fullSummary().log()
    }
}


fun main() {
    when ("OANDA_PAPER") {
        "IEX" -> feedIEX()
        "IEX_LIVE" -> feedIEXLive()
        "YAHOO" -> feedYahoo()
        "ALPACA_BROKER" -> alpacaBroker()
        "ALPACA_FEED" -> alpacaFeed()
        "ALPACA_HISTORIC_FEED" -> alpacaHistoricFeed()
        "ALPACA_ALL" -> allAlpaca()
        "OANDA_BROKER" -> oandaBroker()
        "OANDA_BROKER2" -> oandaBroker2()
        "OANDA_BROKER3" -> oandaBroker3()
        "OANDA_FEED" -> oanda()
        "OANDA_FEED2" -> oanda2()
        "OANDA_FEED3" -> oandaLong()
        "OANDA_LIVE_FEED" -> oandaLive()
        "OANDA_LIVE_RECORD" -> oandaLiveRecord()
        "OANDA_LIVE_PRICES" -> oandaLivePrices()
        "OANDA_PAPER" -> oandaPaperTrading()
        "OANDA_CLOSE" -> oandaPaperTradingClosePositions()
    }
}