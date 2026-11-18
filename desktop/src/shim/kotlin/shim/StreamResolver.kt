// The phone's stream resolver, as the shared account code tells it about a new session
// (see Downloads.kt in this folder). The desktop reaches streams through its relay, which
// keeps no verdicts tied to a session.
package com.opentune.data.innertube

object StreamResolver {
    fun onSessionChanged() = Unit
}
