package nz.mckenzie.sprayday.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineTileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = OfflineTileStore(temp.root)

    @Test
    fun `tiles land under zoom x y paths`() {
        val store = store()

        store.write(14, 1017, 660, byteArrayOf(1, 2, 3))

        assertTrue(File(temp.root, "14/1017/660.webp").isFile)
        assertTrue(store.contains(14, 1017, 660))
    }

    @Test
    fun `reading a tile returns exactly what was written`() {
        val store = store()
        val bytes = byteArrayOf(9, 8, 7, 6)

        store.write(12, 3, 4, bytes)

        assertEquals(listOf<Byte>(9, 8, 7, 6), store.read(12, 3, 4)?.toList())
    }

    @Test
    fun `a tile that is not stored reads as null rather than throwing`() {
        val store = store()

        assertFalse(store.contains(12, 3, 4))
        assertNull(store.read(12, 3, 4))
    }

    @Test
    fun `writing the same tile twice overwrites rather than duplicating`() {
        val store = store()

        store.write(12, 3, 4, byteArrayOf(1, 1, 1))
        store.write(12, 3, 4, byteArrayOf(2))

        assertEquals(1, store.read(12, 3, 4)?.size)
        assertEquals(1L, store.storedTileCount())
    }

    @Test
    fun `counts tiles and bytes across zoom levels`() {
        val store = store()
        store.write(12, 1, 1, ByteArray(100))
        store.write(13, 2, 2, ByteArray(250))
        store.write(14, 3, 3, ByteArray(50))

        assertEquals(3L, store.storedTileCount())
        assertEquals(400L, store.storedBytes())
    }

    @Test
    fun `an empty store reports nothing`() {
        val store = store()

        assertEquals(0L, store.storedTileCount())
        assertEquals(0L, store.storedBytes())
    }

    @Test
    fun `deleting clears every tile`() {
        val store = store()
        store.write(12, 1, 1, ByteArray(10))
        store.write(13, 2, 2, ByteArray(10))

        val deleted = store.deleteAll()

        assertEquals(2, deleted)
        assertFalse(store.contains(12, 1, 1))
        assertEquals(0L, store.storedTileCount())
    }
}
