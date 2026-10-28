package dev.chungjungsoo.gptmobile.data.localruntime.ipc

/** Shared envelope limits for both outbound and inbound local inference IPC. */
internal object RuntimeWireEnvelope {
    fun validate(
        version: Int,
        requestId: String,
        sessionId: String,
        generation: String,
        callId: String,
        sequence: Long
    ) {
        require(
            version == 1 &&
                requestId.length in 1..128 &&
                sessionId.length <= 128 &&
                generation.length <= 128 &&
                callId.length <= 128 &&
                sequence >= 0
        ) { "Unsupported local IPC protocol" }
    }
}
