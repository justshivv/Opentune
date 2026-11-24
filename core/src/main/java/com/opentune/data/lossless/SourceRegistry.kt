package com.opentune.data.lossless

/**
 * Every [StreamSource] playback can ask, in the order they are preferred: the
 * lossless catalogues first, the high-bitrate fallback after them, the order
 * [ExternalStreams] has always searched in. A source joins the app by being
 * implemented and added here; nothing above the registry names a source.
 */
object SourceRegistry {
    /** The built-in sources, best first. Which add-ons are enabled is [AddonStreamSource]'s own business. */
    val all: List<StreamSource> = listOf(AddonStreamSource, JioSaavnStreamSource)

    /** The source with [id], when there is one. */
    fun byId(id: String): StreamSource? = all.firstOrNull { it.id == id }

    /** The sources answering in [tier], in preference order. */
    fun ofTier(tier: SourceTier): List<StreamSource> = all.filter { it.tier == tier }

    /** The sources the current settings and network allow, in preference order. */
    fun eligible(): List<StreamSource> = all.filter { it.eligible() }
    /** The sources of [tier] the current settings and network allow, in preference order. */
    fun eligible(tier: SourceTier): List<StreamSource> = eligible().filter { it.tier == tier }
}
