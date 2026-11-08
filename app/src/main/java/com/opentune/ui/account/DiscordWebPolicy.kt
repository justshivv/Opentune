package com.opentune.ui.account

/** Keep feature detection in sync with the installed WebView, without its mobile-app marker. */
internal fun discordBrowserUserAgent(webViewAgent: String): String = webViewAgent
    .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
    .replace(Regex("\\s+Version/[^ ]+"), "")
    .replace(" Mobile Safari/", " Safari/")

/** A third-party redirect must never be treated as Discord's signed-in page. */
internal fun isDiscordAccountPage(url: String?): Boolean {
    val uri = runCatching { java.net.URI(url ?: return false) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.host !in setOf("discord.com", "www.discord.com")) return false
    if (uri.port != -1 && uri.port != 443) return false
    return uri.path == "/app" || uri.path == "/channels" || uri.path.startsWith("/channels/")
}
