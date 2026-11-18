package org.thoughtcrime.securesms.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XsessionUpdatesTest {
    @Test
    fun choosesHighestCompatibleReleaseWithVerifiedAssetMetadata() {
        val response = """[
            ${release("xsession-build-4585")},
            ${release("xsession-build-4595", digest = "invalid")},
            ${release("xsession-build-4605", draft = true)},
            ${release("xsession-build-4615", url = "https://example.org/Xsession.apk")},
            ${release("xsession-build-4575")},
            ${release("xsession-v1.33.8-circles")}
        ]"""
        assertEquals(4585L, XsessionUpdates.compatibleRelease(response, 4565L)?.versionCode)
        assertNull(XsessionUpdates.compatibleRelease(response, 4585L))
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        digest: String = "sha256:" + "ab".repeat(32),
        url: String = "https://github.com/MRX2827/session-android/releases/download/$tag/Xsession.apk",
    ) = """{
        "tag_name": "$tag",
        "draft": $draft,
        "prerelease": true,
        "assets": [{"name": "Xsession.apk", "browser_download_url": "$url", "size": 100000, "digest": "$digest"}]
    }"""
}
