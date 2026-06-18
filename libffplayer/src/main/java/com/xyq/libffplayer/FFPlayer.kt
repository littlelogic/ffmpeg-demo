package com.xyq.libffplayer

import android.util.Log
import android.view.Surface
import com.xyq.libbase.player.IPlayer
import com.xyq.libbase.player.IPlayerListener

class FFPlayer: IPlayer {

    private var mNativePtr = 0L
    private var mListener: IPlayerListener? = null

    init {
        System.loadLibrary("jwplayer")
    }

    override fun init() {
        mNativePtr = nativeInit()
    }

    override fun setPlayerListener(listener: IPlayerListener) {
        mListener = listener
    }

    override fun prepare(path: String, surface: Surface?) {
        nativePrepare(mNativePtr, path, surface)
    }

    override fun start() {
        nativeStart(mNativePtr)
    }

    override fun resume() {
        nativeResume(mNativePtr)
    }

    override fun pause() {
        nativePause(mNativePtr)
    }

    override fun stop() {
        nativeStop(mNativePtr)
    }

    override fun release() {
        if (mNativePtr == 0L) return
        nativeRelease(mNativePtr)
        mNativePtr = 0L
    }

    override fun seek(position: Double): Boolean {
        return nativeSeek(mNativePtr, position)
    }

    override fun seekAndPause(position: Double): Boolean {
        return nativeSeekAndPause(mNativePtr, position)
    }

    override fun seekAndPlay(position: Double): Boolean {
        return nativeSeekAndPlay(mNativePtr, position)
    }

    override fun setMute(mute: Boolean) {
        if (mNativePtr == 0L) return
        nativeSetMute(mNativePtr, mute)
    }

    override fun setPlayLimit(start: Double, end: Double) {
        Log.i("ww","--FFPlayer-setPlayLimit-"
                +" mNativePtr:"+mNativePtr
                +" start:"+start
                +" end:"+end
        )
        if (mNativePtr == 0L) return
        Log.i("ww","--FFPlayer-setPlayLimit-in"
                +" mNativePtr:"+mNativePtr
                +" start:"+start
                +" end:"+end
        )
        nativeSetPlayLimit(mNativePtr, start, end)
    }

    override fun clearPlayLimit() {
        if (mNativePtr == 0L) return
        nativeClearPlayLimit(mNativePtr)
    }

    override fun getRotate(): Int {
        return nativeGetRotate(mNativePtr)
    }

    override fun getDuration(): Double {
        return nativeGetDuration(mNativePtr)
    }

    override fun getMediaInfo(): String? {
        return nativeGetMediaInfo(mNativePtr);
    }

    private fun onNative_videoTrackPrepared(width: Int, height: Int, displayRatio: Double) {
        mListener?.onVideoTrackPrepared(width, height, displayRatio)
    }

    private fun onNative_videoFrameArrived(width: Int, height: Int, format: Int, y: ByteArray?, u: ByteArray?, v: ByteArray?) {
        mListener?.onVideoFrameArrived(width, height, format, y, u, v)
    }

    private fun onNative_audioTrackPrepared() {
        mListener?.onAudioTrackPrepared()
    }

    /**
     * buffer: audio sample
     * size: audio size
     * timestamp: ms
     */
    private fun onNative_audioFrameArrived(buffer: ByteArray?, size: Int, flush: Boolean) {
        mListener?.onAudioFrameArrived(buffer, size, flush)
    }

    private fun onNative_playProgress(timestamp: Double) {
        mListener?.onPlayProgress(timestamp)
    }

    private fun onNative_playComplete() {
        mListener?.onPlayComplete()
    }

    private external fun nativeInit(): Long

    private external fun nativeSeek(handle: Long, position: Double): Boolean

    private external fun nativeSeekAndPause(handle: Long, position: Double): Boolean

    private external fun nativeSeekAndPlay(handle: Long, position: Double): Boolean

    private external fun nativeSetMute(handle: Long, mute: Boolean)

    private external fun nativeSetPlayLimit(handle: Long, startTimeS: Double, endTimeS: Double)

    private external fun nativeClearPlayLimit(handle: Long)

    private external fun nativePrepare(handle: Long, path: String, surface: Surface?): Boolean

    private external fun nativeStart(handle: Long)

    private external fun nativeResume(handle: Long)

    private external fun nativePause(handle: Long)

    private external fun nativeStop(handle: Long)

    private external fun nativeRelease(handle: Long)

    private external fun nativeGetDuration(handle: Long): Double

    private external fun nativeGetRotate(handle: Long): Int

    private external fun nativeGetMediaInfo(handle: Long): String?
}
