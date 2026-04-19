package com.badmanners.animurglar.ffmpeg

import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.XYSeries
import org.knowm.xchart.style.markers.None
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.math.max

class SyncChartService {

    fun saveFineCharts(
        fineHz: Int,
        originalFine: DoubleArray,
        dubFine: DoubleArray,
        estimatedOffsetSec: Double,
        rawPath: Path,
        alignedPath: Path,
        maxPoints: Int = DEFAULT_FINE_CHART_POINTS,
    ) {
        saveEnvelopeChart(
            path = rawPath,
            title = "Envelope fine (raw)",
            hz = fineHz,
            original = originalFine,
            dub = dubFine,
            dubShiftSec = 0.0,
            maxPoints = maxPoints,
        )
        saveEnvelopeChart(
            path = alignedPath,
            title = "Envelope fine (dub shifted by estimated offset)",
            hz = fineHz,
            original = originalFine,
            dub = dubFine,
            dubShiftSec = -estimatedOffsetSec,
            maxPoints = maxPoints,
        )
    }

    fun saveCoarseCharts(
        coarseHz: Int,
        originalCoarse: DoubleArray,
        dubCoarse: DoubleArray,
        estimatedOffsetSec: Double,
        rawPath: Path,
        alignedPath: Path,
        maxPoints: Int = DEFAULT_COARSE_CHART_POINTS,
    ) {
        saveEnvelopeChart(
            path = rawPath,
            title = "Envelope coarse (raw)",
            hz = coarseHz,
            original = originalCoarse,
            dub = dubCoarse,
            dubShiftSec = 0.0,
            maxPoints = maxPoints,
        )
        saveEnvelopeChart(
            path = alignedPath,
            title = "Envelope coarse (dub shifted by estimated offset)",
            hz = coarseHz,
            original = originalCoarse,
            dub = dubCoarse,
            dubShiftSec = -estimatedOffsetSec,
            maxPoints = maxPoints,
        )
    }

    private fun saveEnvelopeChart(
        path: Path,
        title: String,
        hz: Int,
        original: DoubleArray,
        dub: DoubleArray,
        dubShiftSec: Double,
        maxPoints: Int,
    ) {
        val chart = XYChartBuilder()
            .width(21000)
            .height(2000)
            .title(title)
            .xAxisTitle("Time (sec)")
            .yAxisTitle("Envelope")
            .build()

        chart.styler.defaultSeriesRenderStyle = XYSeries.XYSeriesRenderStyle.Line
        chart.styler.isLegendVisible = true
        chart.styler.markerSize = 0

        val originalSeries = sampleSeries(values = original, hz = hz, shiftSec = 0.0, maxPoints = maxPoints)
        val dubSeries = sampleSeries(values = dub, hz = hz, shiftSec = dubShiftSec, maxPoints = maxPoints)

        chart.addSeries("original", originalSeries.first, originalSeries.second).marker = None()
        chart.addSeries("dub", dubSeries.first, dubSeries.second).marker = None()

        path.parent.createDirectories()
        BitmapEncoder.saveJPGWithQuality(chart, path.toString(), 0.95f)
    }

    private fun sampleSeries(
        values: DoubleArray,
        hz: Int,
        shiftSec: Double,
        maxPoints: Int,
    ): Pair<DoubleArray, DoubleArray> {
        if (values.isEmpty()) {
            return Pair(DoubleArray(0), DoubleArray(0))
        }

        val targetPoints = max(1, maxPoints)
        val stride = max(1, values.size / targetPoints)
        val size = (values.size + stride - 1) / stride
        val x = DoubleArray(size)
        val y = DoubleArray(size)

        var index = 0
        var cursor = 0
        while (cursor < values.size) {
            x[index] = cursor.toDouble() / hz + shiftSec
            y[index] = values[cursor]
            index += 1
            cursor += stride
        }

        return if (index == size) {
            Pair(x, y)
        } else {
            Pair(x.copyOf(index), y.copyOf(index))
        }
    }

    private companion object {
        private const val DEFAULT_FINE_CHART_POINTS = 20_000
        private const val DEFAULT_COARSE_CHART_POINTS = 10_000
    }
}