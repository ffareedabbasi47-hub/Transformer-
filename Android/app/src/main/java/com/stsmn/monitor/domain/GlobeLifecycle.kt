package com.stsmn.monitor.domain

/** Why the 3D globe cannot be used at all. */
enum class GlobeFailure(val message: String) {
    WEBGL_UNSUPPORTED("This phone's WebView does not support 3D graphics (WebGL)."),
    RENDERER_CRASHED("The 3D engine stopped (low memory or GPU problem)."),
    LOAD_TIMEOUT("The globe did not start in time."),
    LOAD_ERROR("The map style could not be loaded."),
    INIT_ERROR("The globe could not be started."),
}

/** Something degraded, but the globe is still usable. */
enum class GlobeNotice(val message: String) {
    NO_INTERNET("No internet: satellite pictures cannot load. Live data is not affected."),
    TILES_FAILED("The map picture service is not answering. Try Streets, or retry later."),
    TERRAIN_FAILED("Terrain relief is unavailable. The globe still works."),
    SCRIPT_ERROR("A minor error was ignored. Details are in Diagnostics."),
}

enum class GlobePhase { INITIALIZING, READY, FATAL }

data class GlobeState(
    val phase: GlobePhase = GlobePhase.INITIALIZING,
    val failure: GlobeFailure? = null,
    val detail: String? = null,
    val notices: Set<GlobeNotice> = emptySet(),
    val attempt: Int = 0,
) {
    val isReady: Boolean get() = phase == GlobePhase.READY
}

/**
 * INITIALIZING -> READY | FATAL;  FATAL -> (user taps Retry) -> INITIALIZING.
 * Rules: a late timeout can never override a successful start, a dead page can never "become ready" again,
 * notices never change the phase, and there is NO automatic retry (so no retry loops).
 */
object GlobeLifecycle {
    fun initial(attempt: Int = 0) = GlobeState(attempt = attempt)

    fun onReady(s: GlobeState): GlobeState = if (s.phase == GlobePhase.INITIALIZING) s.copy(phase = GlobePhase.READY) else s

    fun onFatal(s: GlobeState, failure: GlobeFailure, detail: String? = null): GlobeState = when {
        s.phase == GlobePhase.FATAL -> s                                   // first cause wins, no duplicate reports
        s.phase == GlobePhase.READY && failure != GlobeFailure.RENDERER_CRASHED -> s.copy(notices = s.notices + GlobeNotice.SCRIPT_ERROR)
        else -> s.copy(phase = GlobePhase.FATAL, failure = failure, detail = detail?.take(160))
    }

    fun onTimeout(s: GlobeState): GlobeState =
        if (s.phase == GlobePhase.INITIALIZING) onFatal(s, GlobeFailure.LOAD_TIMEOUT) else s

    fun onRendererGone(s: GlobeState): GlobeState = onFatal(s, GlobeFailure.RENDERER_CRASHED)

    fun onNotice(s: GlobeState, n: GlobeNotice): GlobeState = s.copy(notices = s.notices + n)

    /** Only a user action restarts the globe; the attempt number lets the screen ignore callbacks of older pages. */
    fun retry(s: GlobeState): GlobeState = GlobeState(attempt = s.attempt + 1)

    fun parseFailure(kind: String?): GlobeFailure = GlobeFailure.values().firstOrNull { it.name == kind } ?: GlobeFailure.INIT_ERROR
    fun parseNotice(kind: String?): GlobeNotice = GlobeNotice.values().firstOrNull { it.name == kind } ?: GlobeNotice.SCRIPT_ERROR
}
