package com.opentune.data.addons

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Per-lookup progress, without exposing URLs, tokens or another song's state. */
internal class AddonRequestStatus(val report: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AddonRequestStatus>
}

internal suspend fun reportAddonRequestStage(stage: String) {
    coroutineContext[AddonRequestStatus]?.report?.invoke(stage)
}
