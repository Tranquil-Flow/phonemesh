package org.phonemesh.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** Binary users can find attribution for every newly redistributed accounts/picture component. */
class NoticesTest {
    @Test fun noticesNameTheQrAndPictureComponentsAndTheirLicences() {
        for (name in listOf("ZXing", "Apache-2.0", "ncnn", "Tencent", "BSD-3-Clause", "ThumbHash", "Evan Wallace", "MIT")) {
            assertTrue("missing binary attribution: $name", Notices.TEXT.contains(name))
        }
    }
}
