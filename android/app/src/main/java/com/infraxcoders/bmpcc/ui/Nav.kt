package com.infraxcoders.bmpcc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.infraxcoders.bmpcc.core.CameraProfile
import com.infraxcoders.bmpcc.core.LensProfile

/** The screens of the app. Kept in a simple in-memory back stack. */
sealed interface Dest {
    data object Home : Dest
    data class Session(val sessionId: String) : Dest
    data class Scene(val sessionId: String, val sceneId: String) : Dest
    data class Shot(val sessionId: String, val sceneId: String, val shotId: String) : Dest
    data class Finder(val sessionId: String, val sceneId: String, val shotId: String) : Dest
    class Library(
        tab: LibraryTab = LibraryTab.LENSES,
        val pickCamera: ((CameraProfile) -> Unit)? = null,
        val pickLens: ((LensProfile) -> Unit)? = null,
    ) : Dest {
        // Kept here so search and filters are still set when coming back from a lens or camera page.
        var tab by mutableStateOf(tab)
        var query by mutableStateOf("")
        var filter by mutableStateOf(LensFilter())
    }
    data object CameraControl : Dest
    /** All recce projects (sessions). */
    data object Recces : Dest
    /** Bluetooth search and pairing; continues to [Shoot] once connected. */
    data object Connect : Dest
    /** Live camera control over Bluetooth. */
    data object Shoot : Dest
    /** "New recce" sheet over the camera picture; opens the viewfinder. */
    data object RecceStart : Dest
    /** Measure the phone camera's real angle of view. */
    data object Calibrate : Dest
    /** All saved frames of a scene side by side. */
    data class Compare(val sessionId: String, val sceneId: String) : Dest
    /** Sun planner for a recce / shot (or anywhere). The time and location are shared with the AR view. */
    class Sun(val sessionId: String? = null, val sceneId: String? = null, val shotId: String? = null, time: Long = System.currentTimeMillis()) : Dest {
        var time by mutableStateOf(time)
        var latitude by mutableStateOf<Double?>(null)
        var longitude by mutableStateOf<Double?>(null)
        /** Buildings / hills recorded around the spot (saved to the recce when there is one). */
        var skyline by mutableStateOf<List<com.infraxcoders.bmpcc.core.SkyPoint>>(emptyList())
        var skylineLoaded = false
    }
    class SunAr(val planner: Sun) : Dest
    /** LUT library: built-in looks and imported .cube files. */
    data object Luts : Dest
    /** All recces with a location on a map (optionally centred on one). */
    data class RecceMap(val focusSessionId: String? = null) : Dest
    data class LensInfo(val lensId: String) : Dest
    data class CameraInfo(val cameraId: String) : Dest
    class Coverage(cameraId: String? = null, lensId: String? = null, modeId: String? = null) : Dest {
        /** Kept here (not in the screen) so picks made on the library screen survive navigation. */
        var cameraId by mutableStateOf(cameraId)
        var lensId by mutableStateOf(lensId)
        var modeId by mutableStateOf(modeId)
    }
}

enum class LibraryTab(val label: String) { LENSES("Lenses"), CAMERAS("Cameras") }

class Navigator {
    val stack = mutableStateListOf<Dest>(Dest.Home)
    val current: Dest get() = stack.last()
    fun push(d: Dest) { stack.add(d) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    /** Swaps the current screen for another (so Back skips the one being left). */
    fun replace(d: Dest) { if (stack.size > 1) stack.removeAt(stack.lastIndex); stack.add(d) }
    /** Removes screens about a deleted recce. */
    fun popTo(d: Dest) { while (stack.size > 1 && stack.last() != d) stack.removeAt(stack.lastIndex) }
}

/** Shown for a moment when a screen's recce/scene/shot no longer exists (e.g. deleted): goes back. */
@Composable
fun Gone(nav: Navigator) {
    androidx.compose.runtime.LaunchedEffect(Unit) { nav.pop() }
}

@Composable
fun AppNav(nav: Navigator) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    // Keeps each screen's scroll position etc. while other screens are on top of it.
    val holder = rememberSaveableStateHolder()
    val d = nav.current
    val key = "${nav.stack.lastIndex}-${System.identityHashCode(d)}"
    holder.SaveableStateProvider(key) { Content(nav, d) }
}

@Composable
private fun Content(nav: Navigator, d: Dest) {
    when (d) {
        Dest.Home -> HomeScreen(nav)
        is Dest.Session -> SessionScreen(nav, d.sessionId)
        is Dest.Scene -> SceneScreen(nav, d.sessionId, d.sceneId)
        is Dest.Shot -> ShotScreen(nav, d.sessionId, d.sceneId, d.shotId)
        is Dest.Finder -> ViewfinderScreen(nav, d.sessionId, d.sceneId, d.shotId)
        is Dest.Library -> LibraryScreen(nav, d)
        is Dest.LensInfo -> LensDetailScreen(nav, d.lensId)
        is Dest.CameraInfo -> CameraDetailScreen(nav, d.cameraId)
        is Dest.Coverage -> CoverageScreen(nav, d)
        Dest.CameraControl -> CameraControlScreen(nav)
        Dest.Recces -> RecceListScreen(nav)
        Dest.Connect -> ConnectScreen(nav)
        Dest.Shoot -> ShootScreen(nav)
        Dest.RecceStart -> RecceStartScreen(nav)
        Dest.Calibrate -> CalibrateScreen(nav)
        is Dest.Compare -> CompareScreen(nav, d.sessionId, d.sceneId)
        is Dest.Sun -> SunPlannerScreen(nav, d)
        is Dest.SunAr -> SunArScreen(nav, d.planner)
        Dest.Luts -> LutLibraryScreen(nav)
        is Dest.RecceMap -> RecceMapScreen(nav, d.focusSessionId)
    }
}
