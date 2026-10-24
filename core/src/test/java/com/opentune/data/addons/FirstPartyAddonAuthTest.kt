package com.opentune.data.addons

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPartyAddonAuthTest {
    @Test fun onlyExactOfficialOriginNeedsProof() {
        val origin = FirstPartyAddonAuth.ORIGIN
        assertTrue(FirstPartyAddonAuth.protects("$origin/monochrome/search?q=hello".toHttpUrl()))
        assertTrue(FirstPartyAddonAuth.protects("$origin/archive/stream/recording".toHttpUrl()))
        for (url in listOf(
            "$origin/monochrome/manifest.json", "$origin/archive/health", "$origin/",
            "https://public-addon.example/search?q=OpenTune",
            "https://public-addon.example/auth/web/info",
            "https://losslessbridge.de.deplexo.com/stream/123",
            "https://bluebit.jucepe0929.workers.dev/search?q=test",
            "$origin.evil.example/search", "$origin:8443/search",
            origin.replace("https://", "http://") + "/search",
            "https://public-addon.example/redirect?to=$origin/monochrome/search"
        )) assertFalse(url, FirstPartyAddonAuth.protects(url.toHttpUrl()))
        // No Context, AndroidKeyStore, network or token setup is needed for public sources.
    }

    @Test fun bothOfOpenTunesServersNeedProof() {
        for (origin in FirstPartyAddonAuth.ORIGINS) {
            assertTrue(origin, FirstPartyAddonAuth.protects("$origin/netease/search?q=hello".toHttpUrl()))
            assertFalse(origin, FirstPartyAddonAuth.protects("$origin/netease/manifest.json".toHttpUrl()))
        }
        assertTrue(FirstPartyAddonAuth.protects("https://addon-opentune.rethinknow.in/archive/stream/x".toHttpUrl()))
        assertTrue(FirstPartyAddonAuth.protects("https://opentune-lossless-addon.create-a-new-site-with-sites.workers.dev/archive/stream/x".toHttpUrl()))
        assertFalse(FirstPartyAddonAuth.protects("https://other.rethinknow.in/archive/stream/x".toHttpUrl()))
    }
}
