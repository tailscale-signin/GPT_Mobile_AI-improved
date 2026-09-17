package dev.chungjungsoo.gptmobile.data.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class VoiceInteractionSessionService : VoiceInteractionSessionService() {

    private var currentSession: VoiceInteractionSession? = null

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        // Reuse or clean up existing session reference to prevent leak
        return VoiceInteractionSessionWrapper(this).also {
            currentSession = it
        }
    }

    override fun onDestroy() {
        currentSession = null
        super.onDestroy()
    }

    private class VoiceInteractionSessionWrapper(context: Context) : VoiceInteractionSession(context) {
        override fun onDestroy() {
            super.onDestroy()
        }
    }
}
