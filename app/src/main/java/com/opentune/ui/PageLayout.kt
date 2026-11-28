package com.opentune.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * The window's chrome as AppRoot worked it out ([appLayout]), for the pages
 * under it. Outside AppRoot (a test drawing one page) it is a phone's.
 */
val LocalAppLayout = compositionLocalOf { appLayout(0, 0, hasSong = false) }

/** How the page being composed spreads out: see [PageSpread]. Always [PageSpread.PHONE] on a phone. */
@Composable
fun currentPageSpread(): PageSpread = LocalAppLayout.current.spread
