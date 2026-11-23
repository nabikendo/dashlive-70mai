package com.dashlive.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlinkDetectorTest {

    /** 3 s de amostras a ~12 por segundo (com variação de tempo, como o detector). */
    private fun feed(value: (Long) -> Float): Pair<BlinkDetector, Long> {
        val d = BlinkDetector()
        val rnd = Random(7)
        var t = 0L
        while (t < 3000) {
            d.add(t, value(t))
            t += 70 + rnd.nextInt(30)
        }
        return d to t
    }

    @Test fun turnSignalIsBlinking() {
        // pisca de ~0,72 s: aceso metade do tempo, com ruído
        val rnd = Random(1)
        val (d, now) = feed { t -> (if ((t / 360) % 2 == 0L) 0.12f else 0.01f) + rnd.nextFloat() * 0.01f }
        assertTrue(d.blinking(now))
    }

    @Test fun brakeLightIsNot() {
        val rnd = Random(2)
        val (d, now) = feed { 0.1f + rnd.nextFloat() * 0.02f }
        assertFalse(d.blinking(now))
    }

    @Test fun darkNoiseIsNot() {
        val rnd = Random(3)
        val (d, now) = feed { rnd.nextFloat() * 0.01f }
        assertFalse(d.blinking(now))
    }

    @Test fun streetLampPassingIsNot() {
        // reflexo de poste: acende uma vez e some, sem ritmo
        val (d, now) = feed { t -> if (t in 800..1400) 0.1f else 0.0f }
        assertFalse(d.blinking(now))
    }

    @Test fun flickerTooFastIsNot() {
        // pisca-pisca rápido (LED de painel, ~0,2 s): não é seta
        val (d, now) = feed { t -> if ((t / 100) % 2 == 0L) 0.1f else 0f }
        assertFalse(d.blinking(now))
    }
}
