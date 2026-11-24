package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroSpeechVocabularyTest {
    @Test fun nonDecomposingLatinNamesRemainSpeakableWithOriginalCaptionRanges() {
        val text = "Søren Łukasz Straße Ægir Œuvre Þór Đorđe"
        val projection = KokoroSpeechText.timed(text) { true }
        assertEquals("soren lukasz strasse aegir oeuvre thor dorde", projection.parts!!.joinToString(" "))
        assertEquals(text.split(' '), projection.phrases!!.flatMap { it.wordRanges }.map { text.substring(it) })
        assertNull(projection.observation.failure)
    }

    @Test fun expandedTitlecaseLigaturesDoNotInventCamelBoundariesForDictionaryMisses() {
        val text = "Ægir Œuvre Ĳssel ÆGIR"
        val projection = KokoroSpeechText.timed(text) { false }
        assertEquals("aegir oeuvre ijssel aegir", projection.parts!!.joinToString(" "))
        assertEquals(text.split(' '), projection.phrases!!.flatMap { it.wordRanges }.map { text.substring(it) })
        assertEquals(4, projection.observation.unknownWords)
    }

    @Test fun correctionsComposeWithUnicodeFoldingAndKeepTheOriginalDisplayedTerms() {
        val text = "Søren uses C# and Łukasz"
        val projection = KokoroSpeechText.withPolicy(text, { true }, mapOf("søren" to "sir en", "c#" to "see sharp"), 16, true)
        assertEquals("sir en uses see sharp and lukasz", projection.parts!!.joinToString(" "))
        assertEquals(listOf("Søren", "Søren", "uses", "C#", "C#", "and", "Łukasz"), projection.phrases!!.flatMap { it.wordRanges }.map { text.substring(it) })
    }

    @Test fun canonicallyEquivalentCorrectedNamesRetainTheWholeCombiningSequence() {
        val text = "Jose\u0301 is here"
        val projection = KokoroSpeechText.withPolicy(text, { true }, mapOf("José" to "ho say"), 16, true)
        assertEquals("ho say is here", projection.parts!!.joinToString(" "))
        assertEquals(listOf(0..4, 0..4, 6..7, 9..12), projection.phrases!!.flatMap { it.wordRanges })
    }

    @Test fun technicalNamesAndLowercaseInitialismsAvoidNativeNonsensePronunciations() {
        assertEquals(
            "git hub chat g p t open a i open g l g l m q n n o n n x g p u a p i",
            speak("github ChatGPT OpenAI OpenGL glm qnn onnx gpu api")
        )
        assertEquals("c plus plus c sharp f sharp dot net p c i e", speak("C++ C# F# .NET PCIe"))
    }

    @Test fun modelAndStandardHyphensNeverTurnVersionLabelsIntoNegativeNumbers() {
        assertEquals("g p t four o g p t four u t f eight s h a two hundred fifty six", speak("GPT-4o GPT-4 UTF-8 SHA-256"))
        assertEquals("u t f eight s h a two hundred fifty six r t x five thousand seventy", speak("utf-8 sha-256 RTX-5070"))
        assertEquals("x minus two minus four unknownmodel minus two g p t minus four", speak("x-2 -4 unknownmodel-2 GPT -4"))
    }

    @Test fun ordinaryWordsAndEmphasisNeverBecomeHeuristicLetterSpelling() {
        assertEquals("please read this ram arm us it nasa nato", speak("PLEASE READ THIS ram arm us it NASA NATO"))
        assertEquals(0, KokoroSpeechText.timed("PLEASE READ THIS ram arm us it NASA NATO") { true }.observation.spelledLetters)
        assertEquals("jason ram", speak("JSON RAM"))
    }

    @Test fun nativeDictionaryMissesStillUseTheGraphemeFrontendWithoutLosingWords() {
        val projection = KokoroSpeechText.timed("microvoxel Gráphgear Seeley") { false }
        assertEquals("microvoxel graphgear seeley", projection.parts!!.joinToString(" "))
        assertEquals(3, projection.observation.unknownWords)
        assertEquals(0, projection.observation.spelledLetters)
    }

    @Test fun dottedInitialismsExpandAsOneSourceTermAndRetainMissingLetterFailure() {
        val text = "U.S.A. G.P.U."
        val projection = KokoroSpeechText.timed(text) { it.length == 1 }
        assertEquals("u s a g p u", projection.parts!!.joinToString(" "))
        assertEquals(List(3) { "U.S.A." } + List(3) { "G.P.U." }, projection.phrases!!.flatMap { it.wordRanges }.map { text.substring(it) })
        assertEquals(2, projection.observation.initialisms)
        assertEquals("u s and u k", speak("U.S and U.K"))
        assertEquals(KokoroPronunciationFailure.MISSING_LETTER, KokoroSpeechText.timed("Q.N.N.") { it != "q" }.observation.failure)
    }

    @Test fun commonAbbreviationsExpandWithoutPausingAfterEachLetter() {
        assertEquals("doctor smith and mister jones for example versus professor lee that is approximately ready et cetera", speak("Dr. Smith and Mr. Jones e.g. vs. Prof. Lee i.e. approx. ready etc."))
        assertEquals("for example that is", speak("e.g i.e"))
        assertEquals("missus jones and miz lee", speak("Mrs. Jones and Ms. Lee"))
        assertEquals("one millisecond. two milliseconds.", speak("1 ms. 2 ms."))
    }

    @Test fun ordinalsPreserveNumericMeaningAcrossTensHundredsAndScales() {
        assertEquals("first second third fourth eleventh twelfth thirteenth twenty first fortieth one hundredth one hundred first one thousandth one millionth", speak("1st 2nd 3rd 4th 11th 12th 13th 21st 40th 100th 101st 1000th 1000000th"))
        assertEquals("eleven s t twelve n d", speak("11st 12nd"))
        assertEquals("twenty first", speak("21ST"))
    }

    @Test fun ordinalExpansionsHaveOriginalRangesEvenWhenCrossingThePhraseBudget() {
        val text = "go ".repeat(15) + "101st"
        val projection = KokoroSpeechText.timed(text) { true }
        assertEquals(2, projection.phrases!!.size)
        assertEquals(listOf("101st", "101st", "101st"), projection.phrases.flatMap { it.wordRanges }.takeLast(3).map { text.substring(it) })
        assertTrue(projection.phrases.all { it.speech.length <= 128 && it.wordRanges.size <= 16 })
    }

    @Test fun scientificNotationReadsMantissaAndExponentWithTheirSigns() {
        assertEquals("six point zero two two times ten to the power of twenty three and minus one point two times ten to the power of minus three", speak("6.022e23 and -1.2E-3"))
        assertEquals("zero point five times ten to the power of plus two", speak(".5e+2"))
        assertEquals("minus zero point five and plus zero point two five", speak("-.5 and +.25"))
    }

    @Test fun fractionsAndMixedUnicodeFractionsKeepTheirAmounts() {
        assertEquals("one half three quarters minus two thirds one over zero two over one hundred", speak("1/2 3/4 -2/3 1/0 2/100"))
        assertEquals("one half one third one and one half", speak("½ ⅓ 1½"))
        assertEquals("zero three slash zero four slash two thousand twenty six", speak("03/04/2026"))
        assertEquals("one slash two slash two thousand twenty six", speak("1/2/2026"))
    }

    @Test fun numericSyntaxIsNeverPartiallyInterpretedAsAnIntegerFraction() {
        assertEquals("one slash two point five", speak("1/2.5"))
        assertEquals("one slash two times ten to the power of three", speak("1/2e3"))
        assertEquals("one slash two x", speak("1/2x"))
        assertEquals("one point five slash two point five", speak("1.5/2.5"))
        assertEquals("one half.", speak("1/2."))
    }

    @Test fun scientificAndFractionAmountsOwnUnitAgreementIncludingCompactSuffixes() {
        assertEquals("one times ten to the power of three milliseconds", speak("1e3ms"))
        assertEquals("two times ten to the power of one meters", speak("2e1 m"))
        assertEquals("one times ten to the power of zero millisecond", speak("1e0 ms"))
        assertEquals("one times ten to the power of minus three meters per second squared", speak("1e-3m/s²"))
        assertEquals("two halves meter and two over one meters", speak("2/2 m and 2/1 m"))
    }

    @Test fun superscriptsKeepExponentMeaningInsteadOfMergingIntoTheBase() {
        assertEquals("x to the power of two and ten to the power of minus three and two to the power of twelve", speak("x² and 10⁻³ and 2¹²"))
        assertEquals("x to the power of three", speak("x^3"))
        assertEquals("one square meter and two cubic centimeters", speak("1 m² and 2 cm³"))
        assertEquals("nine point eight meters per second squared", speak("9.8 m/s²"))
        assertEquals("square meters", speak("m²"))
        assertEquals("note one and reference two", speak("Note¹ and reference²"))
    }

    @Test fun subscriptedFormulaElementsAreReadRatherThanSilentlyJoined() {
        assertEquals("h two o c o two o two n two sodium chloride", speak("H₂O CO₂ O₂ N₂ NaCl"))
        val text = "H₂O x² ½"
        val projection = KokoroSpeechText.timed(text) { true }
        assertEquals(List(3) { "H₂O" } + listOf("x") + List(5) { "²" } + List(2) { "½" }, projection.phrases!!.flatMap { it.wordRanges }.map { text.substring(it) })
    }

    @Test fun newNormalizationMaintainsExistingDateCurrencyUnitAndVersionMeaning() {
        assertEquals("november twenty fourth two thousand twenty six four dollars and fifty cents two megabytes per second version zero point nine point sixty six point fifteen", speak("2026-11-24 $4.50 2 MB/s v0.9.66.15"))
        assertEquals("i p one nine two dot one six eight dot one dot one", speak("IP 192.168.1.1"))
    }

    @Test fun whitespaceChunkBoundariesPreserveNewVocabularyAndContext() {
        val inputs = listOf(
            "Dr. Søren uses github and G.P.U. today.",
            "The 21st sample is 1.5e-3 and 3/4 complete.",
            "Use 1 m² and 2 cm³ with H₂O."
        )
        for (text in inputs) {
            val whole = speak(text)
            for (split in text.indices.filter { text[it].isWhitespace() }.map { it + 1 }) {
                val context = KokoroSpeechContext()
                val segments = listOf(text.substring(0, split), text.substring(split))
                val parts = segments.flatMap { segment ->
                    val projection = context.project(segment, { true }, emptyMap(), 16, true)
                    assertNull(projection.observation.failure)
                    assertTrue(projection.phrases!!.flatMap { it.wordRanges }.all { it.first >= 0 && it.last < segment.length })
                    projection.parts!!
                }
                assertEquals("split $split in $text", whole, parts.joinToString(" "))
            }
        }
    }

    @Test fun unsupportedScriptsStillFailVisiblyWithoutSpellingInventedPronunciations() {
        for (text in listOf("已完成", "مرحبا", "Build ١٤١", "μ is a symbol")) {
            val projection = KokoroSpeechText.timed(text) { true }
            assertNull(projection.parts)
            assertEquals(KokoroPronunciationFailure.UNSUPPORTED_SCRIPT, projection.observation.failure)
        }
    }

    @Test fun largeExpansionsRemainBoundedAndDoNotLogTheVocabulary() {
        val input = List(100) { "U.S.A. 1234567890123456789012th microvoxel" }.joinToString(" ")
        val projection = KokoroSpeechText.timed(input) { true }
        assertTrue(projection.phrases!!.all { it.speech.length <= 128 && it.wordRanges.size <= 16 })
        assertTrue(projection.phrases.flatMap { it.wordRanges }.all { it.first >= 0 && it.last < input.length })
        assertTrue(!projection.observation.toString().contains("microvoxel"))
    }

    @Test fun manyCorrectionsWithOneAccentRetainEveryWordAndBoundedOriginalRanges() {
        val text = "x ".repeat(500) + "Søren"
        val replacement = "one two three four five six seven eight"
        val projection = KokoroSpeechText.withPolicy(text, { true }, mapOf("x" to replacement), 16, true)
        assertEquals((replacement + " ").repeat(500) + "soren", projection.parts!!.joinToString(" "))
        assertTrue(projection.phrases!!.all { it.speech.length <= 128 && it.wordRanges.size <= 16 })
        val originals = projection.phrases.flatMap { it.wordRanges }.map { text.substring(it) }
        assertEquals(List(4000) { "x" } + "Søren", originals)
    }

    private fun speak(text: String) = requireNotNull(KokoroSpeechText.timed(text) { true }.parts).joinToString(" ")
}
