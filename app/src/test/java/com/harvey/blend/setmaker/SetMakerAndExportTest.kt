package com.harvey.blend.setmaker

import com.harvey.blend.analysis.MusicalKey
import com.harvey.blend.export.DjExport
import com.harvey.blend.export.PathMapper
import com.harvey.blend.model.EnergyArc
import com.harvey.blend.model.Playlist
import com.harvey.blend.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

private fun track(id: String, key: String, bpm: Double, energy: Int) = Track(
    id = id, uri = "content://x/$id", relativePath = "House/$id & co.aiff", fileName = "$id & co.aiff",
    sizeBytes = 1000, title = "Track $id <x>", artist = "Artist \"$id\"",
    userKey = MusicalKey.fromCamelot(key), userBpm = bpm, userEnergy = energy,
)

class SetMakerTest {
    private val tracks = listOf(
        track("a", "6A", 120.0, 3), track("b", "7A", 121.0, 4), track("c", "7A", 122.0, 5),
        track("d", "8A", 123.0, 6), track("e", "9A", 124.0, 7), track("f", "9B", 124.0, 8),
        track("g", "10A", 125.0, 9), track("h", "9A", 123.0, 6),
    )

    @Test fun keepsAllTracksOnce() {
        repeat(10) { seed ->
            val order = SetMaker.buildOrder(tracks.shuffled(kotlin.random.Random(seed)), EnergyArc.JOURNEY, seed = seed.toLong())
            assertEquals(tracks.map { it.id }.toSet(), order.map { it.id }.toSet())
            assertEquals(tracks.size, order.size)
        }
    }

    @Test fun followsTheArc() {
        val order = SetMaker.buildOrder(tracks.shuffled(kotlin.random.Random(3)), EnergyArc.JOURNEY, seed = 7)
        val firstHalf = order.take(3).mapNotNull { it.energy }.average()
        val peak = order.drop(3).take(3).mapNotNull { it.energy }.average()
        assertTrue("opens lower than it peaks: $firstHalf vs $peak", firstHalf < peak)
    }

    @Test fun respectsLockedTracks() {
        val shuffled = tracks.shuffled(kotlin.random.Random(1))
        val lockedId = shuffled[2].id
        val order = SetMaker.buildOrder(shuffled, EnergyArc.JOURNEY, locked = setOf(lockedId), seed = 1)
        assertEquals(lockedId, order[2].id)
    }

    @Test fun harmonicBeatsClash() {
        val from = track("x", "8A", 124.0, 6)
        val harmonic = SetMaker.score(from, track("y", "9A", 124.0, 6))
        val clash = SetMaker.score(from, track("z", "2A", 124.0, 6))
        assertTrue(harmonic.score > clash.score)
        assertTrue(harmonic.describe(from, track("y", "9A", 124.0, 6)).startsWith("8A→9A"))
    }
}

class ExportTest {
    private val tracks = listOf(track("a", "8A", 123.0, 6), track("b", "9A", 124.0, 7))
    private val byId = tracks.associateBy { it.id }
    private val playlist = Playlist("p1", "Sunset & Deep", trackIds = listOf("a", "b"))

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(xml.byteInputStream())

    @Test fun rekordboxXmlIsValid() {
        val xml = DjExport.rekordboxXml(listOf(playlist), byId, PathMapper("D:\\Music"))
        val doc = parse(xml)
        val t = doc.getElementsByTagName("TRACK").item(0) as org.w3c.dom.Element
        assertEquals("file://localhost/D:/Music/House/a%20%26%20co.aiff", t.getAttribute("Location"))
        assertEquals("Am", t.getAttribute("Tonality"))
        assertEquals("123.00", t.getAttribute("AverageBpm"))
    }

    @Test fun traktorNmlIsValid() {
        val xml = DjExport.traktorNml(listOf(playlist), byId, PathMapper("/Users/david/Music"))
        val doc = parse(xml)
        val loc = doc.getElementsByTagName("LOCATION").item(0) as org.w3c.dom.Element
        assertEquals("/:Users/:david/:Music/:House/:", loc.getAttribute("DIR"))
        assertEquals("Macintosh HD", loc.getAttribute("VOLUME"))
        assertEquals(2, doc.getElementsByTagName("PRIMARYKEY").length)
    }

    @Test fun windowsTraktorPath() {
        val (vol, dir, file) = PathMapper("D:\\Music").traktorLocation("House/a.aiff")
        assertEquals("D:", vol); assertEquals("/:Music/:House/:", dir); assertEquals("a.aiff", file)
    }
}
