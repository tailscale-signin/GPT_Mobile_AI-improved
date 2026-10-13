package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistHandoffTest {
    @Test fun exactSpansPreserveNumbersUnitsNegationAndUnicode() {
        val text = "In 2024, 12 kg was NOT approved; 3.5 μg differed from 3.5 mg. Contrary findings remain unresolved."
        val source = buildJsonObject { put("passage", text) }
        val span = AssistHandoffContract.spans(source).single()
        assertEquals(text, AssistHandoffContract.select(listOf(span), listOf(span.id))?.single()?.text)
        assertNull(AssistHandoffContract.select(listOf(span), listOf("invented")))
        assertNull(AssistHandoffContract.select(listOf(span), listOf(span.id, span.id)))
        val changed = AssistHandoffContract.spans(buildJsonObject { put("passage", text.replace("NOT", "NOW")) }).single()
        assertNull(AssistHandoffContract.select(listOf(changed), listOf(span.id)))
    }
}
