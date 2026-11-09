package com.projectnuke.fusion.knowledge

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebClipCleartextPolicyTest {
    @Test
    fun applicationDeniesCleartextAndWebClipRejectsHttpBeforeConnecting() = runBlocking {
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("example.com"))
        val result = WebClipFetcher().fetch("http://example.com/article")
        assertEquals(WebClipFetchResult.Failed(WebClipFailure.INVALID_URL), result)
    }
}
