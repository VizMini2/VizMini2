// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaTest {

    private fun eval(text: String, vararg vars: Pair<String, Double>): Double? = Formula.parse(text).eval { mapOf(*vars)[it] }

    @Test
    fun arithmeticPrecedenceAndFunctions() {
        assertEquals(7.0, eval("1 + 2 * {a}", "a" to 3.0)!!, 1e-12)
        assertEquals(9.0, eval("(1 + 2) * {a}", "a" to 3.0)!!, 1e-12)
        assertEquals(-4.0, eval("-{a}^2", "a" to 2.0)!!, 1e-12)
        assertEquals(512.0, eval("2^3^{a}", "a" to 2.0)!!, 1e-12) // right-associative power
        assertEquals(5.0, eval("hypot({vx}, {vy})", "vx" to 3.0, "vy" to 4.0)!!, 1e-12)
        assertEquals(90.0, eval("atan2({y}, {x}) * 180 / pi", "x" to 0.0, "y" to 1.0)!!, 1e-9)
        assertEquals(90.0, eval("deg(atan2({y}, {x}))", "x" to 0.0, "y" to 1.0)!!, 1e-9)
        assertEquals(36.0, eval("{speed km} * 3.6", "speed km" to 10.0)!!, 1e-12)
        assertEquals(1.5, eval("{a} % 2", "a" to 3.5)!!, 1e-12)
        assertEquals(2.5e-3, eval("{a} * 2.5e-3", "a" to 1.0)!!, 1e-15)
        assertEquals(listOf("cmd", "odom"), Formula.parse("{cmd} - {odom} + {cmd}").refs)
        // A series is missing: no value.
        assertNull(eval("{a} + {b}", "a" to 1.0))
    }

    @Test
    fun errorsSayWhatIsWrong() {
        for (bad in listOf("", "1 + 2", "{a} +", "({a}", "{a} {b}", "sqrt({a}, 2)", "speed * 2", "{}", "{a} $ 2", "{a")) {
            assertThrows(bad, FormulaException::class.java) { Formula.parse(bad) }
        }
        val e = assertThrows(FormulaException::class.java) { Formula.parse("speed * 2") }
        assertTrue(e.message!!, "{speed}" in e.message!!)
    }

    @Test
    fun runnerComputesOnTheFirstSeriesSamples() {
        val a = SeriesSpec(id = "a", topic = "/a", type = "t", field = "x", label = "a")
        val b = SeriesSpec(id = "b", topic = "/b", type = "t", field = "x", label = "b")
        val diff = SeriesSpec(id = "d", formula = "{a} - {b}", label = "diff")
        val chain = SeriesSpec(id = "c", formula = "{diff} * 10", scale = 2.0)
        val bad = SeriesSpec(id = "x", formula = "{nope} + 1")
        val self = SeriesSpec(id = "s", formula = "{me} + 1", label = "me")
        val buffers = listOf(a, b, diff, chain, bad, self).associate { it.id to SeriesBuffer() }
        val errors = HashMap<String, String>()
        val runner = FormulaRunner(listOf(a, b, diff, chain, bad, self), buffers, errors)
        assertTrue(errors.getValue("x"), "{nope}" in errors.getValue("x"))
        assertTrue(errors.containsKey("s"))

        val s = 1_000_000_000L
        buffers.getValue("b").add(1 * s, 1.0)
        buffers.getValue("a").add(2 * s, 5.0)
        buffers.getValue("a").add(3 * s, 7.0)
        runner.update()
        assertEquals(listOf(2 * s, 3 * s), buffers.getValue("d").all().first.toList())
        assertEquals(listOf(4.0, 6.0), buffers.getValue("d").all().second.toList())
        // In order: the one that uses another formula already sees it computed (and applies its scale).
        assertEquals(listOf(80.0, 120.0), buffers.getValue("c").all().second.toList())

        // Only what is new on the next pass.
        buffers.getValue("b").add((3.5 * s).toLong(), 2.0)
        buffers.getValue("a").add(4 * s, 10.0)
        runner.update()
        assertEquals(listOf(4.0, 6.0, 8.0), buffers.getValue("d").all().second.toList())

        // If the first series starts over, the formula does too.
        buffers.getValue("a").clear()
        buffers.getValue("a").add(1 * s, 3.0)
        runner.update()
        assertEquals(listOf(2.0), buffers.getValue("d").all().second.toList())
    }

    @Test
    fun renamingASeriesUpdatesTheFormulas() {
        val layout = PlotLayout(listOf(PanelSpec(series = listOf(
            SeriesSpec(id = "a", topic = "/a", field = "x", label = "speed"),
            SeriesSpec(id = "f", formula = "{speed} * 3.6 + { speed }")))))
        val renamed = layout.renameReferences("speed", "v$1")
        assertEquals("{v$1} * 3.6 + {v$1}", renamed.allSeries[1].formula)
        // Round trip through JSON with the formula and the header time.
        val back = PlotLayout.fromJson(renamed.copy(headerStamp = true).toJson())
        assertEquals("{v$1} * 3.6 + {v$1}", back.allSeries[1].formula)
        assertTrue(back.allSeries[1].isFormula)
        assertTrue(back.headerStamp)
        assertEquals("{v$1} * 3.6 + {v$1}", back.allSeries[1].displayLabel)
    }

    @Test
    fun decimatorKeepsMinAndMaxPerBucketInTimeOrder() {
        // 1000 samples from 0 to 999 ns, a peak at 503: with 10 buckets 20 samples remain.
        val d = Decimator(0, 999, 10)
        for (t in 0L until 1000L) d.add(t, if (t == 503L) 50.0 else (t % 7).toDouble())
        d.add(5, Double.NaN)
        val b = SeriesBuffer()
        d.writeTo(b)
        val (ts, vs) = b.all()
        assertEquals(20, ts.size)
        assertTrue((1 until ts.size).all { ts[it] > ts[it - 1] })
        assertEquals(50.0, vs.max(), 0.0)
        assertEquals(503L, ts[vs.indexOfFirst { it == 50.0 }])
        assertEquals(0.0, vs.min(), 0.0)
    }

    @Test
    fun spectrumHistogramAndIntegral() {
        // 5 Hz with amplitude 2 sampled at 200 Hz for 4 s: the peak at 5 Hz with a height of ~2.
        val n = 800
        val t = LongArray(n) { it * 5_000_000L }
        val v = DoubleArray(n) { 2 * Math.sin(2 * Math.PI * 5 * it / 200.0) + 1 }
        val (f, a) = PlotMath.spectrum(t, v, t.first(), t.last())!!
        val peak = (1 until a.size).maxByOrNull { a[it] }!!
        assertEquals(5.0, f[peak], 0.3)
        assertEquals(2.0, a[peak], 0.25)
        assertNull(PlotMath.spectrum(LongArray(3), DoubleArray(3), 0, 1))

        val h = PlotMath.histogram(doubleArrayOf(0.0, 0.1, 0.5, 0.99, 1.0, Double.NaN), 2, 0.0, 1.0)
        assertArrayEquals(intArrayOf(2, 3), h)

        // Integral of 2 over 3 s = 6.
        val integ = SeriesFilter(SeriesSpec(transform = SeriesTransform.INTEGRAL))
        var last = 0.0
        for (i in 0..30) last = integ.apply(i * 100_000_000L, 2.0)!!
        assertEquals(6.0, last, 1e-9)
    }

    @Test
    fun stampsReorderingAndMeasure() {
        val m = MsgValue().apply { put("header", MsgValue().apply { put("stamp", MsgValue().apply { put("sec", 12); put("nanosec", 500L) }) }) }
        assertEquals(12_000_000_500L, stampNs(m))
        assertNull(stampNs(MsgValue().apply { put("header", MsgValue().apply { put("stamp", MsgValue().apply { put("sec", 0); put("nanosec", 0L) }) }) }))
        assertNull(stampNs(MsgValue()))

        // Slightly backwards: the sample is discarded; a lot: it starts over.
        val b = SeriesBuffer()
        b.add(10_000_000_000L, 1.0); b.add(9_800_000_000L, 2.0)
        assertEquals(1, b.size())
        b.add(5_000_000_000L, 3.0)
        assertEquals(listOf(3.0), b.all().second.toList())

        assertEquals("Δt +1.500 s", PlotMath.deltaTime(1_500_000_000L))
        assertEquals("3.000  Δ +2.000", PlotMath.measure(1.0, 3.0))
        assertEquals("3.000", PlotMath.measure(null, 3.0))
        assertEquals("—", PlotMath.measure(1.0, null))
    }
}
