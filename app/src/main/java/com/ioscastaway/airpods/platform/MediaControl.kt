package com.ioscastaway.airpods.platform

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Pause and resume whatever is playing.
 *
 * No notification-listener permission needed: [AudioManager.dispatchMediaKeyEvent] delivers a
 * media key to the active media session exactly as a headset button would, and every player
 * that works with a headset works with this. [isPlaying] is the system's own view of the music
 * stream, which is what lets the ear detector pause only things that are actually playing and
 * resume only things it paused.
 */
class MediaControl(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    fun isPlaying(): Boolean = audio.isMusicActive

    fun pause() = press(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun play() = press(KeyEvent.KEYCODE_MEDIA_PLAY)

    private fun press(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
