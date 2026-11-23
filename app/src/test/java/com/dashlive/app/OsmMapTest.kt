package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class OsmMapTest {

    // mapa de mentira em volta de (0, 0): 1 grau de latitude = 110 540 m; no equador 1 de longitude = 111 320 m
    private fun lat(y: Double) = y / OsmMap.M_PER_DEG_LAT
    private fun lon(x: Double) = x / OsmMap.M_PER_DEG_LON

    private fun way(id: Long, tags: String, vararg pts: Triple<Long, Double, Double>): String {
        val nodes = pts.joinToString(",") { it.first.toString() }
        val geom = pts.joinToString(",") { """{"lat":${lat(it.third)},"lon":${lon(it.second)}}""" }
        return """{"type":"way","id":$id,"nodes":[$nodes],"geometry":[$geom],"tags":{$tags}}"""
    }

    private val json = """{"elements":[
        ${way(1, "\"highway\":\"residential\",\"lanes\":\"2\"", Triple(1, 0.0, -100.0), Triple(2, 0.0, 0.0), Triple(3, 0.0, 100.0))},
        ${way(2, "\"highway\":\"residential\"", Triple(3, 0.0, 100.0), Triple(4, 10.0, 200.0))},
        ${way(3, "\"highway\":\"residential\"", Triple(3, 0.0, 100.0), Triple(5, 100.0, 100.0))},
        ${way(4, "\"highway\":\"primary\",\"oneway\":\"yes\",\"lanes\":\"3\",\"bridge\":\"yes\",\"lit\":\"yes\"", Triple(6, 200.0, 0.0), Triple(7, 200.0, 100.0))},
        ${way(5, "\"highway\":\"footway\"", Triple(8, -50.0, 0.0), Triple(9, -50.0, 100.0))},
        {"type":"node","id":10,"lat":${lat(50.0)},"lon":${lon(0.0)},"tags":{"highway":"crossing"}},
        {"type":"node","id":11,"lat":${lat(70.0)},"lon":${lon(0.0)},"tags":{"traffic_calming":"bump"}}
    ]}"""

    private val map = OsmMap.parse(json, 0.0, 0.0)

    @Test fun parsesWaysAndPoints() {
        assertEquals(5, map.ways.size)
        assertEquals(2, map.points.size)
        assertEquals(OsmMap.PointKind.CROSSING, map.points[0].kind)
        assertEquals(OsmMap.PointKind.BUMP, map.points[1].kind)
        val w = map.ways[0]
        assertEquals(100f, w.y(2), 0.5f)
        val p = map.ways[3]
        assertEquals(1, p.oneway)
        assertEquals(3, p.lanes)
        assertTrue(p.bridge && p.lit)
        assertFalse(map.ways[4].drivable)
    }

    @Test fun matchesDirection() {
        val north = map.match(2f, 10f, 0f)!!
        assertEquals(1L, north.way.id)
        assertFalse(north.reverse)
        assertEquals(2f, north.dist, 0.1f)
        val south = map.match(2f, 10f, PI.toFloat())!!
        assertTrue(south.reverse)
    }

    @Test fun neverMatchesAgainstOneway() {
        // perto só da rua de mão única (x = 200), andando para o sul: não pode encaixar nela
        assertNull(map.match(201f, 50f, PI.toFloat()))
        assertNotNull(map.match(201f, 50f, 0f))
    }

    @Test fun corridorFollowsStraightestRoad() {
        val m = map.match(0f, 10f, 0f)!!
        val c = map.corridor(m, ahead = 150f, behind = 30f)
        assertTrue("começa atrás do carro", c.start < -20f)
        assertTrue("vai pelo menos 150 m", c.end >= 150f)
        // no nó (0, 100) segue para (10, 200) (quase reto), não para a rua lateral (100, 100)
        assertEquals(10f, c.xy[c.count * 2 - 2], 0.5f)
        assertEquals(200f, c.xy[c.count * 2 - 1], 0.5f)
        // a faixa de pedestre (0, 50) fica a 40 m à frente do carro, em cima do trecho
        val (s, d) = c.project(0f, 50f)
        assertEquals(40f, s, 0.5f)
        assertEquals(0f, d, 0.1f)
        assertEquals(1, c.lanesAhead(c.segAt(0f)))
        assertEquals(1, c.lanesBack(c.segAt(0f)))
    }

    @Test fun pointOnCorridor() {
        val c = map.corridor(map.match(0f, 0f, 0f)!!)
        val o = FloatArray(3)
        c.at(50f, o)
        assertEquals(0f, o[0], 0.1f)
        assertEquals(50f, o[1], 0.1f)
        assertEquals(0f, o[2], 0.01f) // rumo norte
    }
}
