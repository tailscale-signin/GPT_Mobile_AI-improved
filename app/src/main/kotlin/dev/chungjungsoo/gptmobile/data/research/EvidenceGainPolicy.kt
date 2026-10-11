package dev.chungjungsoo.gptmobile.data.research

/** Stop discovery after repeated rounds add neither canonical sources nor supported claims. */
class EvidenceGainPolicy(
    initialSources: Set<String> = emptySet(),
    initialSupportedClaims: Set<String> = emptySet(),
    private val stagnantRoundLimit: Int = 2,
    private val minimumGain: Int = 1
) {
    init {
        require(stagnantRoundLimit > 0 && minimumGain > 0)
    }

    private var sources = initialSources.toSet()
    private var claims = initialSupportedClaims.toSet()
    private var stagnantRounds = 0

    fun shouldStop(canonicalSources: Set<String>, supportedClaims: Set<String>): Boolean {
        val gain = (canonicalSources - sources).size + (supportedClaims - claims).size
        stagnantRounds = if (gain >= minimumGain) 0 else stagnantRounds + 1
        sources = sources + canonicalSources
        claims = claims + supportedClaims
        return stagnantRounds >= stagnantRoundLimit
    }
}
