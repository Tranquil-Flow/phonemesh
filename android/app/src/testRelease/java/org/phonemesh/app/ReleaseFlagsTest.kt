package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the release BuildConfig only (`:app:testReleaseUnitTest`). */
class ReleaseFlagsTest {
    /** The only switch between "pinned catalog packs only" and "any self-consistent ZIP installs". */
    @Test fun releaseRefusesUnknownPacks() = assertFalse(BuildConfig.ALLOW_UNKNOWN_PACKS)

    /** Release builds never fetch packs over cleartext, and a file name is appended straight to the address. */
    @Test fun releasePackBaseUrlIsHttps() {
        assertTrue(BuildConfig.PACK_BASE_URL, BuildConfig.PACK_BASE_URL.startsWith("https://"))
        assertTrue(BuildConfig.PACK_BASE_URL, BuildConfig.PACK_BASE_URL.endsWith("/"))
    }

    /** Release builds download from a real server: the reserved `.invalid` domain never resolves. */
    @Test fun releasePackBaseUrlIsNotAPlaceholder() =
        assertFalse(BuildConfig.PACK_BASE_URL, Regex("https://[^/]*\\.invalid(:\\d+)?/.*").matches(BuildConfig.PACK_BASE_URL))

    /** Only the test-only screenshot build may show screens to screenshots and recents thumbnails. */
    @Test fun releaseScreensAreSecure() = assertTrue(BuildConfig.SECURE_SCREENS)

    /** Only debug test builds may pretend to be in another country. */
    @Test fun releaseUsesThePhonesRealCountry() = assertEquals("", BuildConfig.FAKE_COUNTRY)
}
