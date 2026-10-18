package com.opentune.data.lossless

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProviderPackageTest {
    @Test fun repositoryAndPackageUrlsCannotRedirectInstallationToAnotherService() {
        assertEquals(ProviderPackages.DEFAULT_REPOSITORY, ProviderPackages.repositoryUrl(ProviderPackages.DEFAULT_REPOSITORY))
        for (url in listOf("http://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json", "https://evil.example/registry.json", ProviderPackages.DEFAULT_REPOSITORY + "?token=x"))
            assertThrows(IllegalArgumentException::class.java) { ProviderPackages.repositoryUrl(url) }
    }
    @Test fun rejectsTamperedPackagesAndTraversalBeforeExecutingCode() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("../escape")); it.write("no".toByteArray()); it.closeEntry() }
        val bytes = out.toByteArray()
        val listing = ProviderListing("amazon", "Amazon", "1", "", "", ProviderPackages.digest(bytes), true)
        assertThrows(IllegalArgumentException::class.java) { ProviderPackages.inspect(bytes, listing) }
        assertThrows(IllegalArgumentException::class.java) { ProviderPackages.inspect(bytes, listing.copy(sha256 = "0".repeat(64))) }
    }
    @Test fun networkPermissionIncludesSubdomainsButNotLookalikesOrCredentials() {
        val hosts = listOf("api.zarz.moe", "*.cloudfront.net")
        assertTrue(ProviderNetwork.allowed("https://cdn.cloudfront.net/audio".toHttpUrl(), hosts))
        for (url in listOf("https://cloudfront.net/audio", "https://evilcloudfront.net/audio", "http://api.zarz.moe/", "https://user@api.zarz.moe/", "https://127.0.0.1/", "https://api.zarz.moe:444/"))
            assertFalse(url, ProviderNetwork.allowed(url.toHttpUrl(), hosts))
    }
    @Test fun errorDiagnosticsKeepStructuredCauseAndExcludeCredentialFields() {
        val e = LosslessHttp.HttpFailure(403, JSONObject().put("origin", "provider").put("code", "PROVIDER_AUTH_FAILED").put("error", "secret url and grant").put("session_secret", "secret").toString())
        val d = ProviderDiagnostic.from(e)
        assertTrue(d.summary().contains("PROVIDER_AUTH_FAILED"))
        assertTrue(d.guidance().contains("upstream account"))
        assertFalse(d.summary().contains("secret"))
        assertFalse(d.guidance().contains("grant"))
        assertEquals("", ProviderDiagnostic.from(LosslessHttp.HttpFailure(403, """{"code":"https://secret.example/?grant=x"}""")).code)
    }
}
