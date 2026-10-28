package dev.personal.airplayrx

/** Kotlin side of jni_bridge.cpp. Loading the class loads libairplayrx.so. */
object NativeBridge {
    /** Receives the services UxPlay registers through the dns_sd shim. */
    interface ServiceListener {
        fun onServiceRegistered(name: String, type: String, port: Int, txt: List<Pair<String, String?>>)
        fun onServiceUnregistered(name: String, type: String)
    }

    @Volatile
    var serviceListener: ServiceListener? = null

    /** Receives mirrored video; owned by ReceiverService. */
    @Volatile
    var videoDecoder: VideoDecoder? = null

    /** Receives mirrored audio; owned by ReceiverService. */
    @Volatile
    var audioPlayer: AudioPlayer? = null

    init {
        System.loadLibrary("airplayrx")
    }

    /**
     * Versions of UxPlay, libplist and OpenSSL, after a quick self-test of each.
     * Logs "native ready: …" on success. Cheap; safe on any thread.
     */
    @JvmStatic
    external fun nativeInfo(): String

    /**
     * Starts the AirPlay server and registers its services (via [serviceListener]).
     * Returns the TCP port, or a negative error. Blocking — call on a worker thread.
     * Idempotent.
     */
    @JvmStatic
    external fun nativeStart(name: String, mac: String, width: Int, height: Int, fps: Int): Int

    /** Unregisters the services and stops the server. Blocking. Idempotent. */
    @JvmStatic
    external fun nativeStop()

    /**
     * Copies the frame currently being offered through [onVideoFrame] into [dst] (a direct
     * buffer, normally a MediaCodec input buffer). Returns its length, or -1 if it does not fit.
     * Only valid on the mirror thread, from inside [onVideoFrame].
     */
    @JvmStatic
    external fun nativeCopyFrame(dst: java.nio.ByteBuffer): Int

    /** UxPlay mirror thread, once per H.264 access unit. Returns a VideoDecoder submit code. */
    @JvmStatic
    @Suppress("unused") // called from JNI
    fun onVideoFrame(len: Int, ptsUs: Long): Int =
        videoDecoder?.submit(len, ptsUs) ?: VideoDecoder.NOT_READY

    /**
     * UxPlay mirror or httpd thread; may repeat the same value. Synchronized so the check and the
     * transition are one step: otherwise a stop on the httpd thread and a quick reconnect on the
     * mirror thread can interleave and lose the start.
     */
    @JvmStatic
    @Synchronized
    @Suppress("unused") // called from JNI
    fun onMirrorState(running: Boolean) {
        if (MirrorState.active.value == running) return
        android.util.Log.i(TAG, "mirroring ${if (running) "started" else "stopped"}")
        if (running) {
            videoDecoder?.onSessionStart()
        } else {
            videoDecoder?.onSessionEnd()
            audioPlayer?.onSessionEnd()
        }
        MirrorState.setActive(running)
    }

    /** UxPlay audio thread, once per AAC-ELD packet (copy it with [nativeCopyFrame]). */
    @JvmStatic
    @Suppress("unused") // called from JNI
    fun onAudioPacket(len: Int, ptsUs: Long) {
        audioPlayer?.submit(len, ptsUs)
    }

    /** UxPlay httpd thread: iPhone volume as a linear gain 0..1. */
    @JvmStatic
    @Suppress("unused") // called from JNI
    fun onAudioVolume(gain: Float) {
        audioPlayer?.setVolume(gain)
    }

    /** UxPlay thread: the iPhone paused/seeked; drop buffered audio. */
    @JvmStatic
    @Suppress("unused") // called from JNI
    fun onAudioFlush() {
        audioPlayer?.flush()
    }

    /** UxPlay mirror thread: the size the iPhone reports (a hint until the decoder knows). */
    @JvmStatic
    @Suppress("unused") // called from JNI
    fun onVideoSize(width: Int, height: Int) {
        MirrorState.setVideoSize(width, height)
    }

    /**
     * Called from native code on the thread running [nativeStart]/[nativeStop].
     * [txt] is the raw DNS-SD TXT record (null on unregister).
     */
    @JvmStatic
    @Suppress("unused") // called from JNI
    fun onServiceChanged(registered: Boolean, name: String, type: String, port: Int, txt: ByteArray?) {
        val listener = serviceListener ?: return
        if (registered) {
            listener.onServiceRegistered(name, type, port, TxtRecord.parse(txt ?: ByteArray(0)))
        } else {
            listener.onServiceUnregistered(name, type)
        }
    }
}
