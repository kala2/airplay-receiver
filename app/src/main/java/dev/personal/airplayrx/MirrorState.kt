package dev.personal.airplayrx

import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide hand-off between the session owner ([ReceiverService] / [VideoDecoder]) and the
 * UI ([MainActivity]), so neither holds a reference to the other.
 */
object MirrorState {
    private val _active = MutableStateFlow(false)
    private val _videoSize = MutableStateFlow<Size?>(null)

    /** True while the iPhone is mirroring. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Size of the decoded picture (after cropping), for aspect-correct layout. */
    val videoSize: StateFlow<Size?> = _videoSize.asStateFlow()

    fun setActive(active: Boolean) {
        _active.value = active
        if (!active) _videoSize.value = null
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) _videoSize.value = Size(width, height)
    }

    /** Quality choices for the idle screen: what this TV + decoder support, and what is in use. */
    data class Quality(val options: List<Resolutions.Option>, val choice: String, val active: Resolutions.Option)

    private val _quality = MutableStateFlow<Quality?>(null)
    val quality: StateFlow<Quality?> = _quality.asStateFlow()

    fun setQuality(q: Quality) {
        _quality.value = q
    }

    /** True while MainActivity is started (visible). Main thread writes, any thread reads. */
    @Volatile
    var uiVisible = false

    /** Receives the activity's video surface. Called on the main thread. */
    fun interface SurfaceListener {
        fun onSurface(surface: Surface?)
    }

    @Volatile
    var surfaceListener: SurfaceListener? = null
        set(value) {
            field = value
            value?.onSurface(surface)
        }

    /** The SurfaceView's surface while it exists, else null. */
    @Volatile
    var surface: Surface? = null
        private set

    /**
     * Main thread, from SurfaceHolder callbacks. The listener runs synchronously so the decoder
     * stops using a surface before surfaceDestroyed() returns.
     */
    fun setSurface(surface: Surface?) {
        this.surface = surface
        surfaceListener?.onSurface(surface)
    }
}
