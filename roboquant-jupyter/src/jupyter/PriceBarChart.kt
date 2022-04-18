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

package org.roboquant.jupyter

import com.google.gson.JsonObject
import org.roboquant.brokers.Trade
import org.roboquant.common.*
import org.roboquant.feeds.Feed
import org.roboquant.feeds.PriceBar
import org.roboquant.feeds.filter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Plot the price-bars (candlesticks) of an asset found in a [feed] and optionally the [trades] made for that same
 * asset. This will only plot candlesticks if the feed also contains price actions of the type PriceBar.
 * If this is not the case you can use the [PriceChart] instead to plot prices.
 *
 * By default, the chart will use a linear timeline, meaning gaps like a weekend will show-up. this can be disabled
 * by setting [useTime] to false.
 */

interface PriceBarChartDataProvider {
    fun provide(
        timeframe: Timeframe,
        asset: Asset,
        useTime: Boolean,
        indicators: List<PriceBarChart.Indicator>
    ): List<List<Any>>
}

class FeedChartDataProvider(private val feed: Feed) : PriceBarChartDataProvider {
    override fun provide(
        timeframe: Timeframe,
        asset: Asset,
        useTime: Boolean,
        indicators: List<PriceBarChart.Indicator>
    ): List<List<Any>> {
        val entries = feed.filter<PriceBar>(timeframe) { it.asset == asset }
        val data = entries.mapIndexed { index, it ->
            val (now, price) = it
            val direction = if (price.close >= price.open) 1 else -1
            val time = if (useTime) now else now.atZone(Config.defaultZoneId).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            buildList<Any> {
                addAll(listOf(time, price.open, price.high, price.low, price.close, price.volume, direction))
                indicators.forEach {
                    val offset = entries.size - it.values.size
                    if(index < offset) {
                        add("-")
                    } else {
                        add(it.values[index - offset])
                    }
                }
            }
        }
        return data
    }
}

class PriceBarChart(
    private val dataProvider: PriceBarChartDataProvider,
    private val asset: Asset,
    private val trades: Collection<Trade> = emptyList(),
    private val timeframe: Timeframe = Timeframe.INFINITE,
    private val useTime: Boolean = true,
    private val indicators: List<Indicator> = emptyList(),
) : Chart() {


    init {
        height = 700
    }

    /**
     * Generate mark points that will highlight when a trade happened.
     */
    private fun markPoints(): List<Map<String, Any>> {
        val t = trades.filter { it.asset == asset && timeframe.contains(it.time) }
        val d = mutableListOf<Map<String, Any>>()
        for (trade in t) {
            val time = if (useTime) trade.time else trade.time.atZone(Config.defaultZoneId).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val price = Amount(asset.currency, trade.price).toBigDecimal()
            val styleJsonObject = JsonObject()
            val entry = mapOf(
                "value" to trade.quantity.asQuantity,
                "xAxis" to time,
                "yAxis" to price,
                "itemStyle" to styleJsonObject.apply {
                    val color = when {
                        trade.pnlValue == 0.0 -> "rgb(0,205,50)"
                        trade.pnlValue <= 0.0 -> "rgb(220,20,60)"
                        else -> "rgb(46,139,87)"
                    }
                    addProperty("color", color)
                },
                "orderId" to trade.orderId,
            )
            d.add(entry)
        }
        return d
    }

    override fun renderCustomData(): String {
        val trades = trades.filter { it.asset == asset && timeframe.contains(it.time) }

        val headers = {
            val headersCells = listOf("ID", "Side", "Time", "Price", "Quantity", "P&L")
                .joinToString(separator = "\n") {
                """
                <th scope="col" class="text-sm font-medium text-gray-900 px-6 py-4 text-left">
                    $it
                </th>
                """
            .trimIndent()
            }
            """
            <thead class="bg-white border-b">
              <tr>
                $headersCells
              </tr>
            </thead>
            """.trimIndent()
        }

        val tradeRows = {
            trades.joinToString(separator = "\n") {
                val side = if (it.pnlValue == 0.0) "BUY" else "SELL"
                val time = it.time.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(
                    FormatStyle.MEDIUM))
                val price = it.priceAmount
                val quantity = it.quantity
                val pnlText = if(side == "BUY") "-" else it.pnl
                val pnlTextColor = if(side == "BUY") "gray" else if(it.pnlValue < 0) "red" else "green"
            """
                <tr class="bg-white border-b transition duration-300 ease-in-out hover:bg-gray-100">
                  <td class="text-sm text-gray-900 font-light px-6 py-4 whitespace-nowrap">
                    ${it.orderId}
                  </td>
                  <td class="text-sm text-gray-900 font-light px-6 py-4 whitespace-nowrap">
                    $side
                  </td>
                  <td class="text-sm text-gray-900 font-light px-6 py-4 whitespace-nowrap">
                    $time
                  </td>
                  <td class="text-sm text-gray-900 font-light px-6 py-4 whitespace-nowrap">
                    $price
                  </td>
                  <td class="text-sm text-gray-900 font-light px-6 py-4 whitespace-nowrap">
                    $quantity
                  </td>
                  <td class="text-sm text-$pnlTextColor-400 px-6 py-4 whitespace-nowrap">
                    $pnlText
                  </td>
                </tr>
                }
                """.trimIndent()
            }
        }
        return """
             <div class="flex flex-col px-48">
              <div class="overflow-x-auto sm:-mx-6 lg:-mx-8">
                <div class="py-2 inline-block min-w-full sm:px-6 lg:px-8">
                  <div class="overflow-hidden">
                    <table class="min-w-full">
                    ${headers()}
                    ${tradeRows()}
                    </table>
                  </div>
                </div>
              </div>
             </div>            
        """.trimIndent()
    }

    /** @suppress */
    override fun renderOption(): String {

        val line = reduce(dataProvider.provide(timeframe, asset, useTime, indicators))
        val lineData = gsonBuilder.create().toJson(line)
        val timeframe = ""

        val marks = markPoints()
        val markData = gsonBuilder.create().toJson(marks)
        val xAxisType = if (useTime) "time" else "category"

        return """
                {
                dataset: {
                    source: $lineData
                },
                title: {
                    text: '${asset.symbol} $timeframe'
                },
                tooltip: {
                    trigger: 'axis',
                    axisPointer: {
                        type: 'line'
                    }
                },
                ${renderToolbox()},
                grid: [
                    {
                        left: 80,
                        right: '3%',
                        bottom: 200
                    },
                    {
                        left: 80,
                        right: '3%',
                        height: 80,
                        bottom: 80
                    }
                ],
                xAxis: [
                    {
                        type: '$xAxisType',
                        scale: true,
                        boundaryGap: true,
                        axisLine: {onZero: false},
                        splitLine: {show: false},
                        splitNumber: 20,
                        min: 'dataMin',
                        max: 'dataMax'
                    },
                    {
                        type: '$xAxisType',
                        gridIndex: 1,
                        scale: true,
                        boundaryGap: true,
                        axisLine: {onZero: false},
                        axisTick: {show: false},
                        splitLine: {show: false},
                        axisLabel: {show: false},
                        splitNumber: 20,
                        min: 'dataMin',
                        max: 'dataMax'
                    }
                ],
                yAxis: [
                    {
                        scale: true,
                        splitArea: {
                            show: true
                        }
                    },
                    {
                        scale: true,
                        gridIndex: 1,
                        splitNumber: 2,
                        axisLabel: {show: false},
                        axisLine: {show: false},
                        axisTick: {show: false},
                        splitLine: {show: false}
                    }
                ],
                dataZoom: [
                    {
                        type: 'inside',
                        xAxisIndex: [0, 1],
                        start: 0,
                        end: 100
                    },
                    {
                        show: true,
                        xAxisIndex: [0, 1],
                        type: 'slider',
                        bottom: 10,
                        start: 0,
                        end: 100
                    }
                ],
                visualMap: {
                    show: false,
                    seriesIndex: 1,
                    dimension: 6,
                    pieces: [{
                        value: 1,
                        color: 'green'
                    }, {
                        value: -1,
                        color: 'red'
                    }]
                },
                series: [
                    {
                        type: 'candlestick',
                        name: '${asset.symbol}',
                        itemStyle: {
                            color: 'green',
                            color0: 'red',
                            borderColor: 'green',
                            borderColor0: 'red'
                        },
                         markPoint: {
                                data: $markData,
                                itemStyle : {
                                    color: "yellow"
                                }
                        },
                        encode: {
                            x: 0,
                            y: [1, 4, 3, 2]
                        },
                          
                    },
                    {
                        name: 'Volume',
                        type: 'bar',
                        xAxisIndex: 1,
                        yAxisIndex: 1,
                        itemStyle: {
                            color: '#7fbe9e'
                        },
                        large: true,
                        encode: {
                            x: 0,
                            y: 5
                        }
                    },
                    ${renderIndicators()}
                ]
                };
                """.trimIndent()
    }

    private fun renderIndicators(): String {
        val gson = gsonBuilder.setPrettyPrinting().create()
        val elements = indicators.mapIndexed { index, it ->
            val indicatorElement = gson.toJsonTree(it)
            val encode = JsonObject()
            encode.addProperty("x", 0)
            encode.addProperty("y", index + 7)

            indicatorElement.asJsonObject.add("encode", encode)
            indicatorElement.asJsonObject.remove("data")
            return@mapIndexed indicatorElement
        }
        return elements.joinToString(",\n") {
            gson.toJson(it)
        }
    }

    data class Indicator(
        val name: String,
        val values: List<Double>,
    )
}
