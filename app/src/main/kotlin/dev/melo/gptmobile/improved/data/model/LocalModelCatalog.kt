package dev.melo.gptmobile.improved.data.model

data class LocalModelCatalogEntry(
    val id: String,
    val name: String,
    val description: String = "",
    val downloadUrl: String = "",
    val hfRepoId: String = "",
    val fileName: String = "",
    val requiredRamBytes: Long = 0L
)

object LocalModelCatalog {
    const val DEFAULT_MODEL_ID = "gemma-2-2b-it-gpu"

    val entries: List<LocalModelCatalogEntry> = listOf(
        LocalModelCatalogEntry(
            id = DEFAULT_MODEL_ID,
            name = "Gemma 2 2B IT (GPU)",
            description = "Google Gemma 2 2B Instruct quantized for LiteRT mobile GPU.",
            hfRepoId = "google/gemma-2-2b-it-gpu-int8",
            fileName = "gemma-2-2b-it-gpu-int8.bin"
        )
    )
}
