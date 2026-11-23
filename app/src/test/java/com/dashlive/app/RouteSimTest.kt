package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimTest {

    @Test
    fun distanciaDeUmGrauDeLongitudeNoEquadorEhCercaDe111km() {
        val d = RouteSim.distance(0.0, 0.0, 0.0, 1.0)
        assertTrue("d=$d", kotlin.math.abs(d - 111_195.0) < 200.0)
    }

    @Test
    fun rumoNorteEhZeroELesteEhNoventa() {
        assertEquals(0f, RouteSim.bearing(0.0, 0.0, 0.001, 0.0), 0.01f)
        assertEquals(90f, RouteSim.bearing(0.0, 0.0, 0.0, 0.001), 0.01f)
    }

    @Test
    fun leGeoJsonDeFeatureEDaLinha() {
        val json = """{"type":"Feature","properties":{},"geometry":{"type":"LineString",
            "coordinates":[[-47.0,-6.0],[-47.0,-6.001]]}}"""
        val sim = RouteSim.fromGeoJson(json)
        assertNotNull(sim)
        assertTrue(sim!!.length > 100.0)
    }

    @Test
    fun semDoisPontosNaoHaRota() {
        assertNull(RouteSim.fromGeoJson("""{"type":"LineString","coordinates":[[-47.0,-6.0]]}"""))
    }

    @Test
    fun posicaoNoMeioDoTrechoEhIntermediaria() {
        val sim = RouteSim.fromGeoJson("""{"type":"LineString","coordinates":[[-47.0,-6.0],[-47.0,-6.002]]}""")!!
        val mid = sim.at(sim.length / 2)
        assertEquals(-6.001, mid.lat, 1e-6)
        assertEquals(-47.0, mid.lon, 1e-9)
        assertEquals(180f, mid.bearing, 0.1f) // andando para o sul
    }

    @Test
    fun passaDoFimVoltaAoComeco() {
        val sim = RouteSim.fromGeoJson("""{"type":"LineString","coordinates":[[-47.0,-6.0],[-47.0,-6.002]]}""")!!
        val start = sim.at(0.0)
        val wrapped = sim.at(sim.length)
        assertEquals(start.lat, wrapped.lat, 1e-9)
        assertEquals(start.lon, wrapped.lon, 1e-9)
    }

    @Test
    fun pontosDuplicadosNaoQuebram() {
        // a rota real tem pontos repetidos seguidos (trecho de comprimento zero)
        val sim = RouteSim.fromGeoJson(
            """{"type":"LineString","coordinates":[[-47.0,-6.0],[-47.0,-6.0],[-47.0,-6.001],[-47.0,-6.001],[-47.0,-6.002]]}""",
        )!!
        for (m in listOf(0.0, 10.0, 55.0, sim.length, sim.length * 3 + 1)) {
            val p = sim.at(m)
            assertTrue(p.lat.isFinite() && p.lon.isFinite())
            assertTrue(p.bearing >= 0f && p.bearing < 360f)
        }
    }

    @Test
    fun velocidadePadraoEhSessentaQuilometrosPorHora() {
        assertEquals(60.0, RouteSim.SPEED_KMH, 0.0)
    }
}
