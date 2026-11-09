package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputLineageRegistryTest {
    @Test fun onlyIdenticalTextInTheSameRunCanInheritOwnership() {
        val registry = OutputLineageRegistry()
        val model = OutputLineage(OutputOrigin.MODEL_TEXT, "invocation")
        val fingerprint = outputFingerprint("answer")
        registry.resolve("run", fingerprint, model)
        assertEquals(model, registry.resolve("run", fingerprint, OutputLineage()))
        assertEquals(OutputLineage(), registry.resolve("other-run", fingerprint, OutputLineage()))
        assertEquals(OutputLineage(), registry.resolve("run", outputFingerprint("answer\nConnection failed"), OutputLineage()))
    }

    @Test fun identicalOutputFromTwoInvocationsDoesNotChooseTheFirstOrLatest() {
        val registry = OutputLineageRegistry()
        val fingerprint = outputFingerprint("same answer")
        val first = OutputLineage(OutputOrigin.MODEL_TEXT, "first")
        val latest = OutputLineage(OutputOrigin.MODEL_TEXT, "latest")
        assertEquals(first, registry.resolve("run", fingerprint, first))
        assertEquals(latest, registry.resolve("run", fingerprint, latest))
        assertEquals(OutputLineage(OutputOrigin.MODEL_TEXT), registry.resolve("run", fingerprint, OutputLineage()))
        registry.resolve("run", fingerprint, latest)
        assertEquals(OutputLineage(OutputOrigin.MODEL_TEXT), registry.resolve("run", fingerprint, OutputLineage()))
    }

    @Test fun conflictingOriginsAndEvictedEvidenceCannotManufactureUniqueModelLineage() {
        val registry = OutputLineageRegistry(2)
        val fingerprint = outputFingerprint("same answer")
        registry.resolve("run", fingerprint, OutputLineage(OutputOrigin.MODEL_TEXT, "first"))
        registry.resolve("run", fingerprint, OutputLineage(OutputOrigin.SYSTEM_ERROR))
        assertEquals(OutputLineage(), registry.resolve("run", fingerprint, OutputLineage()))
        registry.resolve("other", outputFingerprint("other1"), OutputLineage(OutputOrigin.PROGRESS_NOTICE))
        registry.resolve("other", outputFingerprint("other2"), OutputLineage(OutputOrigin.PROGRESS_NOTICE))
        registry.resolve("run", fingerprint, OutputLineage(OutputOrigin.MODEL_TEXT, "latest"))
        assertEquals(OutputLineage(), registry.resolve("run", fingerprint, OutputLineage()))
    }

    @Test fun privacyRevocationAndClearRemoveInMemoryReceipts() {
        val registry = OutputLineageRegistry()
        val fingerprint = outputFingerprint("answer")
        val model = OutputLineage(OutputOrigin.MODEL_TEXT, "invocation")
        registry.resolve("run", fingerprint, model)
        registry.removeRun("run")
        assertEquals(OutputLineage(), registry.resolve("run", fingerprint, OutputLineage()))
        registry.resolve("run", fingerprint, model)
        registry.clear()
        assertEquals(OutputLineage(), registry.resolve("run", fingerprint, OutputLineage()))
    }
}
