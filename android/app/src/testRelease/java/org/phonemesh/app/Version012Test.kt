package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pin the version Android actually packages for this release. */
class Version012Test {
    @Test fun releaseVersionCodeIsThree() = assertEquals(3, BuildConfig.VERSION_CODE)
    @Test fun releaseVersionNameIs012() = assertEquals("0.1.2", BuildConfig.VERSION_NAME)
}
