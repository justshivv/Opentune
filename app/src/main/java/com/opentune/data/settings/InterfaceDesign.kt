package com.opentune.data.settings

import kotlinx.serialization.Serializable

/** Optional presentation only. Never replaces the user's existing UI choices. */
@Serializable
enum class InterfaceDesign(val label: String, val summary: String) {
    CURRENT("Current", "Your original OpenTune look and animations"),
    FLOW("Flow", "A listening dashboard, collection shelves and a studio player"),
    EDITORIAL("Editorial", "Serif headings, quiet surfaces and an album-first layout"),
    GLASS("Glass", "Translucent surfaces, outlined cards and a luminous dock"),
}

@Serializable
enum class DesignMotion(val label: String, val summary: String) {
    INHERIT("Existing", "Keep your individual animation choices"),
    CALM("Calm", "Short fades and gentle touch feedback"),
    FLUID("Fluid", "Smooth page movement and a soft spring in the dock"),
    EXPRESSIVE("Expressive", "More playful touch feedback and a springier dock"),
}

@Serializable
enum class DesignDensity(val label: String) { COMFORTABLE("Comfortable"), COMPACT("Compact") }

@Serializable
enum class DesignDockVisibility(val label: String) { FOLLOW_SCROLL("Follow scrolling"), ALWAYS("Always visible") }

@Serializable
data class DesignOptions(
    val style: InterfaceDesign = InterfaceDesign.CURRENT,
    val motion: DesignMotion = DesignMotion.INHERIT,
    val density: DesignDensity = DesignDensity.COMFORTABLE,
    val dockVisibility: DesignDockVisibility = DesignDockVisibility.FOLLOW_SCROLL,
)

/** Apply only the additive design fields, including when returning to Current. */
fun InterfaceSettings.withDesign(options: DesignOptions): InterfaceSettings = copy(design = options)
