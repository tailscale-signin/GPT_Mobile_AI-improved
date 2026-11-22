package dev.chungjungsoo.gptmobile.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateIdentityDetectorTest {
    @Test
    fun relationshipAndParenthesizedSelfNamesAreDiscovered() {
        val detector = PrivateIdentityDetector()
        val text = "My girlfriend Alice and I, (Sam) are dating and are looking for ideas."
        assertEquals(listOf("Alice", "Sam"), values(detector, text))
    }

    @Test
    fun introductionProtectsRepeatedReferencesWithinAndAfterItsMessage() {
        val detector = PrivateIdentityDetector()
        val text = "My boyfriend Alex said Alex would meet me."
        assertEquals(listOf("Alex", "Alex"), values(detector, text))
        assertEquals(listOf("ALEX", "Alex"), values(detector, "ALEX agrees. Alex's plan works."))
    }

    @Test
    fun seedingAllMessagesProtectsReferencesBeforeAnIntroduction() {
        val detector = PrivateIdentityDetector()
        val earlier = "Alice enjoys museums."
        detector.seed(listOf(earlier, "My partner is Alice."))
        assertEquals(listOf("Alice"), values(detector, earlier))
    }

    @Test
    fun familyIntroductionsProtectSingleAndFullNames() {
        val detector = PrivateIdentityDetector()
        val text = "My sister Jane Smith and my father Luis are visiting."
        assertEquals(listOf("Jane Smith", "Luis"), values(detector, text))
        assertEquals(listOf("Jane", "Jane Smith", "Luis"), values(detector, "Jane and Jane Smith both refer to the same person; Luis is her father."))
    }

    @Test
    fun explicitLowerCaseNamesAndUnicodeNamesKeepTheirExactText() {
        val detector = PrivateIdentityDetector()
        val text = "My name is élodie. My partner is named renée. My brother Zo\u0308e is here."
        assertEquals(listOf("élodie", "renée", "Zo\u0308e"), values(detector, text))
        assertEquals(listOf("ÉLODIE", "RENÉE", "ZO\u0308E"), values(detector, "ÉLODIE, RENÉE and ZO\u0308E agree."))
    }

    @Test
    fun learnedNamesDoNotReplaceSubstringsOrPartsOfOtherNames() {
        val detector = PrivateIdentityDetector()
        detector.seed(listOf("Call me Ann. My partner is Sam."))
        val text = "Ann, Ann's and Ann’s; Annette, annual, XAnn, Ann-Marie, d'Ann, Sam, same and sample."
        assertEquals(listOf("Ann", "Ann", "Ann", "Sam"), values(detector, text))
    }

    @Test
    fun apostrophesHyphensAndSingleCharacterNamesAreWholeIdentities() {
        val detector = PrivateIdentityDetector()
        val text = "My mother Anne-Marie and my father O’Neil have a child 李."
        assertEquals(listOf("Anne-Marie", "O’Neil", "李"), values(detector, text))
        assertEquals(listOf("ANNE-MARIE", "O’NEIL", "李"), values(detector, "ANNE-MARIE, O’NEIL and 李."))
    }

    @Test
    fun ordinaryRelationshipSentencesDoNotInventNames() {
        val detector = PrivateIdentityDetector()
        assertTrue(detector.ranges("My girlfriend is looking for ideas. My partner likes museums. I am happy.").isEmpty())
    }

    @Test
    fun clearingRequestRemovesLearnedNames() {
        val detector = PrivateIdentityDetector()
        detector.seed(listOf("My girlfriend Alice."))
        detector.clear()
        assertTrue(detector.ranges("Alice enjoys museums.").isEmpty())
    }

    private fun values(detector: PrivateIdentityDetector, text: String): List<String> =
        detector.ranges(text).map { text.substring(it) }
}
