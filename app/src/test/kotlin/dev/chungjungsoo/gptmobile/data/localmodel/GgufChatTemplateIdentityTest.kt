package dev.chungjungsoo.gptmobile.data.localmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class GgufChatTemplateIdentityTest {
    @Test fun identifiesCommonTemplateFamiliesWithoutTreatingIdentityAsCompatibility() {
        assertEquals("gemma", GgufChatTemplateIdentity.describe("<start_of_turn>user").substringBefore(':'))
        assertEquals("chatml", GgufChatTemplateIdentity.describe("{{ '<|im_start|>' }}").substringBefore(':'))
        assertEquals("custom", GgufChatTemplateIdentity.describe("custom jinja template").substringBefore(':'))
        assertEquals("missing", GgufChatTemplateIdentity.describe(null))
    }
}
