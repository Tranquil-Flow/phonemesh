package org.phonemesh.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the release BuildConfig only (`:app:testReleaseUnitTest`). */
class ReleaseFlagsTest {
    /** The only switch between "pinned catalog packs only" and "any self-consistent ZIP installs". */
    @Test fun releaseRefusesUnknownPacks() = assertFalse(BuildConfig.ALLOW_UNKNOWN_PACKS)

    /** Release builds never fetch packs over cleartext (the placeholder is `https://packs.invalid/`). */
    @Test fun releasePackBaseUrlIsHttps() =
        assertTrue(BuildConfig.PACK_BASE_URL, BuildConfig.PACK_BASE_URL.startsWith("https://"))
}
