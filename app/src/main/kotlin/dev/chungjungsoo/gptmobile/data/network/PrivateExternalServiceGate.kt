package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin

/** Also covers an already running tool, its queued requests, retries and redirects. */
internal val PrivateExternalServiceGate = createClientPlugin("PrivateExternalServiceGate") {
    on(SendingRequest) { _, _ -> PrivacyModeStore.requireUnprotectedTransport() }
}
