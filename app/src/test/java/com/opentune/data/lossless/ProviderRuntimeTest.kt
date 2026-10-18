package com.opentune.data.lossless

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProviderRuntimeTest {
    private lateinit var bootstrap: String
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProviderRuntime.init(context)
        bootstrap = context.assets.open("lossless/runtime.js").bufferedReader().use { it.readText() }
    }
    private fun pkg(id: String): ProviderPackage {
        val versions = mapOf("amazon" to "2.3.11", "qobuz-web" to "1.2.21", "tidal-web" to "1.2.7")
        val hashes = mapOf("amazon" to "39ec454b07b5d881aa844ab6f2aa5eed6cff120bdecf49d10bd2386355de3329", "qobuz-web" to "d957759c849d9631caa029cf2279635d44bd468af11a2df9cd13088cd721afc2", "tidal-web" to "83e3aa93c59a8e16fa3da85d972cb8ba95e4f29992bd756b18f61269714080db")
        val path = File("build/provider-fixtures/$id.sflx").takeIf { it.isFile } ?: File("app/build/provider-fixtures/$id.sflx")
        assertTrue("Run scripts/fetch-provider-fixtures.py before these integration tests", path.isFile)
        return ProviderPackages.inspect(path.readBytes(), ProviderListing(id, id, versions.getValue(id), "", "", hashes.getValue(id), true))
    }
    @Test fun realPackagesCompileAndRegisterSearchAndDownloadFunctions() {
        for (id in ProviderPackages.supported) {
            val pkg = pkg(id)
            ProviderRuntime.validate(pkg)
            assertTrue(pkg.qualities.isNotEmpty())
            assertEquals(id, pkg.id)
        }
    }
    @Test fun realAmazonPackageRequestsTicketThenAudioAndPreservesProviderError() {
        val pkg = pkg("amazon"); val requests = mutableListOf<JSONArray>()
        val result = ProviderRuntime.evaluate(pkg.script, bootstrap,
            "JSON.stringify(extension.download('B012345678','best','/job/provider.flac',function(){},{preparedContext:{web_metadata:{id:'B012345678',name:'Fixture',artists:'Artist'}}}))") { op, args ->
            when (op) {
                "sha256" -> ExtensionProtocol.hash(args.getString(0))
                "remaining" -> 30000
                "cancelled" -> false
                "signed" -> {
                    requests += args
                    if (args.getString(1) == "/tickets") JSONObject().put("statusCode", 200).put("body", "{\"ticket_id\":\"fixture-ticket\"}")
                    else JSONObject().put("statusCode", 403).put("error", "Request denied").put("code", "PROVIDER_AUTH_FAILED").put("retryable", false)
                }
                else -> JSONObject().put("status", 404).put("statusCode", 404).put("ok", false).put("body", "{}")
            }
        } as JSONObject
        assertFalse(result.getBoolean("success")); assertEquals("authentication_error", result.getString("error_type"))
        assertEquals(2, requests.size)
        assertEquals("/tickets", requests[0].getString(1))
        assertEquals("/dl/amazeamazeamaze", requests[1].getString(1))
        assertEquals("fixture-ticket", requests[1].getJSONObject(3).getString("X-Zarz-Ticket"))
        assertEquals("flac", JSONObject(requests[1].getString(2)).getString("codec"))
    }
    @Test fun engineHasNoJavaBridgeAndStopsInfiniteLoops() {
        assertEquals("undefined,undefined", ProviderRuntime.evaluate("", bootstrap, "JSON.stringify(typeof java+','+typeof Packages)"))
        assertThrows(ProviderRuntime.Abort::class.java) { ProviderRuntime.evaluate("while(true){}", bootstrap, "'null'", timeoutMs = 40) }
    }
    @Test fun providerCatalogTracksKeepSeparateIdsAndMetadata() {
        val json = JSONObject("""{"id":"12345","name":"A track","artists":"An artist","duration_ms":190000,"item_type":"track"}""")
        val qobuz = ProviderRuntime.parseTrack(json, "qobuz-web")!!
        val tidal = ProviderRuntime.parseTrack(json, "tidal-web")!!
        assertNotEquals(qobuz.key, tidal.key)
        assertEquals("lossless:qobuz-web:12345", qobuz.song().videoId)
        assertNull(ProviderRuntime.parseTrack(json.put("item_type", "album"), "qobuz-web"))
    }
}
