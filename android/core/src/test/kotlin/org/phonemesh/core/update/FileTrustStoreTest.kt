package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The phone's durable trust slots as two files: whole writes by rename, nothing half-written ever visible. */
class FileTrustStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun dir() = File(tmp.root, "updates")

    @Test fun anUnwrittenSlotIsNullAndASavedSlotComesBackExactly() {
        val s = FileTrustStore(dir())
        assertNull(s.load(0)); assertNull(s.load(1))
        s.save(1, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), s.load(1))
        assertNull(s.load(0))
        s.save(1, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), FileTrustStore(dir()).load(1))
    }

    @Test fun onlyTheTwoSlotsExist() {
        val s = FileTrustStore(dir())
        for (bad in listOf(-1, 2, 7)) {
            try { s.load(bad); fail() } catch (_: IllegalArgumentException) { }
            try { s.save(bad, byteArrayOf(1)); fail() } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun aLeftoverHalfWrittenTempFileIsNeverReadAsASlot() {
        val s = FileTrustStore(dir())
        s.save(0, byteArrayOf(5, 5))
        File(dir(), "trust-0.tmp").writeBytes(byteArrayOf(1))
        File(dir(), "trust-1.tmp").writeBytes(byteArrayOf(2))
        val again = FileTrustStore(dir())
        assertArrayEquals(byteArrayOf(5, 5), again.load(0))
        assertNull(again.load(1))
    }

    @Test fun aFailedSaveKeepsTheOldBytesAndSaysSo() {
        val s = FileTrustStore(dir())
        s.save(0, byteArrayOf(7, 7, 7))
        // A directory where the temp file must go makes the write fail.
        File(dir(), "trust-0.tmp").mkdirs()
        File(dir(), "trust-0.tmp/x").writeBytes(byteArrayOf(1))
        try { s.save(0, byteArrayOf(8)); fail("must fail") } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(7, 7, 7), s.load(0))
    }

    @Test fun anOversizedFileIsNotMistakenForARecord() {
        val s = FileTrustStore(dir())
        dir().mkdirs()
        File(dir(), "trust-0.bin").writeBytes(ByteArray(FileTrustStore.MAX_BYTES + 1))
        assertEquals(FileTrustStore.MAX_BYTES + 1, s.load(0)!!.size)
        assertTrue(UpdateTrust.open(s, TestCatalogs.roots, 3, TestCatalogs.baseline) is UpdateTrust.Opened.Corrupt)
    }

    @Test fun theWholeLifeOfATrustStateSurvivesAProcessRestart() {
        val first = (UpdateTrust.open(FileTrustStore(dir()), TestCatalogs.roots, 3, TestCatalogs.baseline) as UpdateTrust.Opened.Ready).trust
        val v2 = TestCatalogs.pack("voice-fr-siwis", 2)
        assertTrue(first.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(v2))) is UpdateTrust.CatalogResult.Accepted)
        assertTrue(first.recordInstalled(v2))
        val reopened = (UpdateTrust.open(FileTrustStore(dir()), TestCatalogs.roots, 3, TestCatalogs.baseline) as UpdateTrust.Opened.Ready).trust
        assertEquals(2L, reopened.state.sequence)
        assertEquals(2L, reopened.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertArrayEquals(first.state.encode(), reopened.state.encode())
    }

    @Test fun aTornSecondSlotFallsBackToTheWholeOne() {
        val store = FileTrustStore(dir())
        val t = (UpdateTrust.open(store, TestCatalogs.roots, 3, TestCatalogs.baseline) as UpdateTrust.Opened.Ready).trust
        val good = t.state.encode()
        // slot 1 holds the first state; a later write cut short in slot 0 (by an old-style non-atomic writer) is just damage.
        File(dir(), "trust-0.bin").writeBytes(good.copyOf(good.size / 2))
        val again = UpdateTrust.open(FileTrustStore(dir()), TestCatalogs.roots, 3, TestCatalogs.baseline)
        assertTrue(again is UpdateTrust.Opened.Ready)
        assertArrayEquals(good, (again as UpdateTrust.Opened.Ready).trust.state.encode())
    }
}
