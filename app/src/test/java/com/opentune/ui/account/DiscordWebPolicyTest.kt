package com.opentune.ui.account

import org.junit.Assert.*
import org.junit.Test

class DiscordWebPolicyTest {
    @Test fun browserIdentityRetainsTheActualChromiumVersionOnEveryOem() {
        for (device in listOf("SM-S928B", "Pixel 9", "moto g")) {
            val ua = "Mozilla/5.0 (Linux; Android 15; $device Build/AP3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.48 Mobile Safari/537.36"
            val browser = discordBrowserUserAgent(ua)
            assertTrue(browser.contains("Chrome/142.0.7444.48"))
            assertTrue(browser.contains("X11; Linux x86_64"))
            assertFalse(browser.contains("Android"))
            assertFalse(browser.contains("Version/4.0"))
            assertFalse(browser.contains("Mobile"))
        }
    }

    @Test fun onlyDiscordHttpsAccountPagesCanCompleteSignIn() {
        listOf("https://discord.com/app", "https://discord.com/channels/@me", "https://www.discord.com/channels/123/456").forEach {
            assertTrue(it, isDiscordAccountPage(it))
        }
        listOf(null, "http://discord.com/app", "https://example.com/channels/@me", "https://discord.com.evil.test/app",
            "https://discord.com@evil.test/app", "https://discord.com:8443/app", "https://discord.com/application", "https://discord.com/login").forEach {
            assertFalse(it, isDiscordAccountPage(it))
        }
    }
}
