package dev.chungjungsoo.gptmobile.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtpCatalogTest {
    @Test fun multiTokenPredictionIsSeparateFromMediaTek() {
        assertFalse(CatalogEntry(id = "gemma-mediatek", supportedAccelerators = listOf("MediaTek NPU")).hasMtp())
        assertFalse(CatalogEntry(id = "attempt-model").hasMtp())
        assertFalse(CatalogEntry(id = "qwen-mtp-int4").hasMtp())
        assertTrue(CatalogEntry(supportsMtp = true).hasMtp())
        assertFalse(CatalogEntry(supportedAccelerators = listOf("MTP")).hasMtp())
    }
}
