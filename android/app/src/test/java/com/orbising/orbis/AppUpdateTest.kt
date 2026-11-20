package com.orbising.orbis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    @Test fun parsesOnlyPublishedPersonalReleaseAssets() {
        val info = parseReleaseMetadata("""
            {
              "tag_name":"v0.1.61", "draft":false, "prerelease":false,
              "assets":[
                {"name":"orbis.apk","browser_download_url":"https://example.test/orbis.apk"},
                {"name":"orbis.apk.version.json","browser_download_url":"https://example.test/version.json"}
              ]
            }
        """, """{"version":"0.1.61","versionCode":62,"sha256":"${"a".repeat(64)}"}""")
        assertEquals("0.1.61", info?.version)
        assertEquals(62, info?.versionCode)
    }

    @Test fun rejectsDraftAndPrereleaseReleaseShapes() {
        assertNull(parseReleaseMetadata("{\"draft\":true,\"assets\":[]}", "{}"))
        assertNull(parseReleaseMetadata("{\"prerelease\":true,\"assets\":[]}", "{}"))
    }
}
