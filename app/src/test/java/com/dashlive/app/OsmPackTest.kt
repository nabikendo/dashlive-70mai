package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmPackTest {
    // duas ruas: uma no centro de Wanderlândia (TO), outra a ~3 km; um semáforo no centro
    private val chunkA = """{"elements":[
        {"type":"way","id":1,"nodes":[10,11],"tags":{"highway":"residential","name":"Rua A"},
         "geometry":[{"lat":-6.8520,"lon":-47.9660},{"lat":-6.8510,"lon":-47.9650}]},
        {"type":"node","id":50,"lat":-6.8515,"lon":-47.9655,"tags":{"highway":"traffic_signals"}}
    ]}"""
    private val chunkB = """{"elements":[
        {"type":"way","id":1,"nodes":[10,11],"tags":{"highway":"residential","name":"Rua A"},
         "geometry":[{"lat":-6.8520,"lon":-47.9660},{"lat":-6.8510,"lon":-47.9650}]},
        {"type":"way","id":2,"nodes":[20,21],"tags":{"highway":"primary","name":"BR-153"},
         "geometry":[{"lat":-6.8250,"lon":-47.9660},{"lat":-6.8240,"lon":-47.9650}]}
    ]}"""

    private fun info() = OsmPack.Info("R336809", "Wanderlândia", -6.9, -48.0, -6.8, -47.9, 2, 0, 0, 2, 2)

    @Test fun mergeRemovesWaysRepeatedInNeighbourChunks() {
        val (json, ways) = OsmPack.merge(listOf(chunkA, chunkB))
        assertEquals(2, ways)
        val p = OsmPack.parse(json, info())
        assertEquals(2, p.wayCount)
    }

    @Test fun extractKeepsOnlyTheSquareAroundThePosition() {
        val p = OsmPack.parse(OsmPack.merge(listOf(chunkA, chunkB)).first, info())
        val m = p.extract(-6.8515, -47.9655, 450)
        assertEquals(listOf(1L), m.ways.map { it.id })   // a BR-153, a 3 km, fica de fora
        assertEquals(1, m.points.size)
        assertEquals(OsmMap.PointKind.SIGNAL, m.points[0].kind)
        // coordenadas em metros a partir da posição (≈ 55 m para leste e 55 m para o norte no fim da rua)
        val w = m.ways[0]
        assertEquals(55.3f, w.x(1), 1f)
        assertEquals(55.3f, w.y(1), 1f)
    }

    @Test fun chunksCoverTheBoxInPiecesOfAtMostStep() {
        val c = OsmPack.chunks(-7.0, -48.1, -6.75, -47.85, 0.05)
        assertEquals(25, c.size)
        assertTrue(c.all { it[2] - it[0] <= 0.0500001 && it[3] - it[1] <= 0.0500001 })
        assertEquals(-7.0, c.first()[0], 1e-9); assertEquals(-47.85, c.last()[3], 1e-9)
    }

    @Test fun clipLimitsBigMunicipalityAroundTheCenter() {
        // Wanderlândia: município de ~0,52° × 0,46°; fica só ±12 km em volta da cidade
        val b = OsmPack.clip(-7.146, -48.197, -6.623, -47.7345, -6.8523, -47.9654, 12_000.0)
        assertEquals(-6.8523 - 12_000 / OsmMap.M_PER_DEG_LAT, b[0], 1e-6)
        assertTrue(b[2] - b[0] < 0.22 && b[3] - b[1] < 0.22)
    }

    @Test fun infoRoundTripsThroughJson() {
        val i = OsmPack.Info.fromJson(info().toJson())
        assertEquals("Wanderlândia", i.name)
        assertTrue(i.covers(-6.85, -47.96))
        assertTrue(i.complete)
    }

    @Test fun relationIdFromLinkOrNumber() {
        assertEquals(336809L, OsmPacks.relationId("https://www.openstreetmap.org/relation/336809#map=16/-6.85110/-47.96257"))
        assertEquals(336809L, OsmPacks.relationId("R336809"))
        assertEquals(336809L, OsmPacks.relationId(" 336809 "))
        assertEquals(null, OsmPacks.relationId("Wanderlândia"))
    }

    @Test fun packKeepsBuildingsWallsAndTreesOutOfRoads() {
        val json = """{"elements":[
            {"type":"way","id":1,"nodes":[10,11],"tags":{"highway":"residential"},
             "geometry":[{"lat":-6.8520,"lon":-47.9660},{"lat":-6.8510,"lon":-47.9650}]},
            {"type":"way","id":3,"nodes":[30,31,32,30],"tags":{"building":"yes","building:levels":"2"},
             "geometry":[{"lat":-6.8515,"lon":-47.9655},{"lat":-6.8515,"lon":-47.9654},{"lat":-6.8516,"lon":-47.9654},{"lat":-6.8515,"lon":-47.9655}]},
            {"type":"way","id":4,"nodes":[40,41],"tags":{"barrier":"fence"},
             "geometry":[{"lat":-6.8518,"lon":-47.9660},{"lat":-6.8518,"lon":-47.9650}]},
            {"type":"node","id":5,"lat":-6.8517,"lon":-47.9657,"tags":{"natural":"tree"}}
        ]}"""
        val p = OsmPack.parse(json, info())
        assertEquals(1, p.wayCount)
        val m = p.extract(-6.8515, -47.9655, 200)
        assertEquals(1, m.ways.size)
        assertEquals(1, m.buildings.size)
        assertEquals(7f, m.buildings[0].height, 0.01f) // 2 andares: 2 × 3 m + 1 m
        assertEquals(1, m.walls.size)
        assertEquals(1.2f, m.walls[0].height, 0.01f)
        assertEquals(1, m.trees.size)
    }
}
