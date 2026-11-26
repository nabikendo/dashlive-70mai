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

    @Test fun straightRoadHasNoTurnAhead() {
        // a rua do mapa de mentira segue quase reta (5,7° no último trecho): nenhuma curva de 30°
        val c = map.corridor(map.match(0f, 0f, 0f)!!)
        assertNull(map.nextTurn(c))
    }

    @Test fun turnAheadIsRightAndWhere() {
        // rua de (0, -100) a (0, 100) e depois para nordeste (45°): curva à direita a ~100 m
        val m = OsmMap.parse("""{"elements":[
            ${way(1, "\"highway\":\"residential\"", Triple(1, 0.0, -100.0), Triple(2, 0.0, 0.0), Triple(3, 0.0, 100.0))},
            ${way(2, "\"highway\":\"residential\"", Triple(3, 0.0, 100.0), Triple(4, 100.0, 200.0))}
        ]}""", 0.0, 0.0)
        val c = m.corridor(m.match(0f, 0f, 0f)!!)
        val t = m.nextTurn(c)!!
        assertTrue(t.right)
        assertTrue("curva a ${t.dist} m", t.dist in 100f..110f)
        assertEquals(45f, t.deg, 3f)
    }

    @Test fun pointsAheadInOrder() {
        // faixa de pedestre a 50 m e lombada a 70 m, ambas em cima da rua; nada atrás
        val c = map.corridor(map.match(0f, 0f, 0f)!!)
        val ahead = map.nextPoints(c)
        assertEquals(OsmMap.PointKind.CROSSING, ahead[0].kind)
        assertEquals(50f, ahead[0].dist, 0.5f)
        assertEquals(OsmMap.PointKind.BUMP, ahead[1].kind)
        assertEquals(70f, ahead[1].dist, 0.5f)
        val desc = map.describeAhead(c)!!
        assertTrue(desc, desc.contains("lombada em 70 m"))
    }

    @Test fun radarIsAPoint() {
        val m = OsmMap.parse("""{"elements":[{"type":"node","id":20,"lat":${lat(30.0)},"lon":${lon(0.0)},"tags":{"highway":"speed_camera"}}]}""", 0.0, 0.0)
        assertEquals(1, m.points.size)
        assertEquals(OsmMap.PointKind.RADAR, m.points[0].kind)
    }

    @Test fun countsRadarsAhead() {
        // dois radares na própria rua, a 120 m e a 200 m à frente do carro
        val m = OsmMap.parse("""{"elements":[
            ${way(1, "\"highway\":\"residential\"", Triple(1, 0.0, -100.0), Triple(2, 0.0, 0.0), Triple(3, 0.0, 300.0))},
            {"type":"node","id":30,"lat":${lat(120.0)},"lon":${lon(0.0)},"tags":{"highway":"speed_camera"}},
            {"type":"node","id":31,"lat":${lat(200.0)},"lon":${lon(0.0)},"tags":{"highway":"speed_camera"}}
        ]}""", 0.0, 0.0)
        val c = m.corridor(m.match(0f, 0f, 0f)!!)
        val desc = m.describeAhead(c)!!
        assertTrue(desc, desc.contains("2 radares, o próximo em 120 m"))
    }
}
