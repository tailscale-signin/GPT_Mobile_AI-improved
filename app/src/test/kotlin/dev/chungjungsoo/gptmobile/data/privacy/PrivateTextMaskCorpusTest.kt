package dev.chungjungsoo.gptmobile.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic tasks cover both disclosure prevention and useful task text. */
class PrivateTextMaskCorpusTest {
    @Test
    fun ordinaryGreetingDoesNotInventProtectedContent() {
        val result = roundTrip("Hello!")
        assertEquals("Hello!", result.text)
        assertTrue(result.receipt.ranges.isEmpty())
    }

    @Test
    fun contextualSurnameDoesNotHideAnOrdinaryModal() {
        val input = "My name is Clara May. May said that I may request a refund."
        val result = roundTrip(input)
        hidden(result, "Clara May")
        assertFalse(result.text.contains("May said"))
        retained(result, "My name is", "said that I may request a refund.")
        val fullName = input.indexOf("Clara May")
        val surname = input.indexOf("May said")
        assertEquals(
            listOf(PrivacyProtectedRange(fullName, fullName + "Clara May".length), PrivacyProtectedRange(surname, surname + "May".length)),
            result.receipt.ranges
        )
    }

    @Test
    fun relationshipNamesStayLocalWithoutRemovingTravelIntent() {
        val result = roundTrip("My partner Maya and I want to go travelling. Suggest a quiet beach.")
        hidden(result, "Maya")
        retained(result, "My partner", "and I want to go travelling", "Suggest a quiet beach")
        assertTrue(result.text.contains("_PERSON_"))
    }

    @Test
    fun introductionOrdinalBirthdayAndDateRequestKeepTheirStructure() {
        val result = roundTrip("My name is Alex Rivera. My birthday is in March 8th. My partner's name is Maya and I need ideas for a date.")
        hidden(result, "Alex", "Rivera", "March 8th", "Maya")
        retained(result, "My name is", "My birthday is in", "and I need ideas for a date")
        assertTrue(result.text.contains("_DATE_"))
    }

    @Test
    fun residenceIsRecognizedAndEarlierSearchReferencesAreProtected() {
        val mask = allCategories()
        val earlier = "Any cafes near Ottawa would work."
        val later = "I live in Ottawa."
        mask.seedIdentities(listOf(earlier, later))
        val first = mask.protectWithReceipt(earlier)
        val second = mask.protectWithReceipt(later)
        hidden(first, "Ottawa")
        hidden(second, "Ottawa")
        retained(first, "Any cafes near", "would work")
        assertEquals(1, mask.issuedMarkers().size)
        assertEquals("REDACT_RESIDENCE_KEEP_SEARCH_REGION", mask.summary().locationPolicy)
        val restore = mask.sealWithReceipts()
        assertEquals(earlier, restore(first.text).text)
        assertEquals(later, restore(second.text).text)
    }

    @Test
    fun explicitSearchRegionIsRetainedUntilItIsLinkedToAResidence() {
        val result = roundTrip("Recommend public museums near Halifax.")
        assertEquals("Recommend public museums near Halifax.", result.text)
        val linked = roundTrip("Any places near Halifax where I live would work.")
        hidden(linked, "Halifax")
        val stricter = PrivateTextMask(setOf(PrivacyCategory.LOCATIONS), emptyList(), PrivateLocationPolicy.REDACT_CONTEXTUAL_REGIONS)
        assertFalse(stricter.protect("Recommend museums near Halifax.").contains("Halifax"))
    }

    @Test
    fun emailReplyHidesNamesContactsAndDeadlineButKeepsCompletedWork() {
        val input = """
            Reply: I got the license fixed! Thank you for the extension. I will have the project done by next Tuesday.

            From: Dana Brooks <dana.brooks@college.example>
            To: Alex Rivera <alex@college.example>
            Subject: Extension

            Hi Dana,
            Thank you for the update.
        """.trimIndent()
        val result = roundTrip(input)
        hidden(result, "Dana", "Brooks", "Alex", "Rivera", "college.example", "next Tuesday")
        retained(result, "I got the license fixed!", "Thank you for the extension", "I will have the project done by", "Subject: Extension", "Thank you for the update")
    }

    @Test
    fun instructionsToUsePlaceholdersAreNotMasked() {
        val input = "Please reply and use the placeholders."
        assertEquals(input, roundTrip(input).text)
    }

    @Test
    fun studentPersonalNarrativeIsAbstractedWithoutLosingTheExtensionRequest() {
        val input = """
            Reply: Hey Dr. Rivera,

            The last week has been rough mentally for me. My family and personal life are going through a rough patch.
            I have a caretaker role for one of my younger brothers who has been deemed not fit to keep themselves alive.
            I've contacted the student helpline, but I want to ask whether I may resubmit the texture assignment.
        """.trimIndent()
        val result = roundTrip(input)
        hidden(result, "Rivera", "rough mentally", "rough patch", "caretaker", "younger brothers", "keep themselves alive", "student helpline")
        retained(result, "Reply: Hey Dr.", "I want to ask whether I may resubmit the texture assignment")
        assertTrue(result.text.contains("_HEALTH_CONTEXT_"))
    }

    @Test
    fun contractPartiesLongNamesDomainsAndAddressFieldsAreComplete() {
        val input = """
            Summary: Northlake Games Publishing
            Registered address: 4321 Cedar Crescent, Unit 7, 12345 Westhaven; Website: https://northlake.example
            Company registration: 7654321098. Email: contact@northlake.example.
            Represented by Mr. Arman Nabil Karim Farid Haddad.
            Alex Rivera (hereinafter “Independent Contractor”)
            The Independent Contractor shall provide professional services.
        """.trimIndent()
        val result = roundTrip(input)
        hidden(result, "Northlake", "4321", "Cedar", "Westhaven", "7654321098", "northlake.example", "Arman", "Nabil", "Karim", "Farid", "Haddad", "Alex", "Rivera")
        retained(result, "Summary:", "Registered address:", "Company registration:", "Represented by Mr.", "Independent Contractor", "shall provide professional services")
    }

    @Test
    fun contractHeadingsAndScopeSurviveWhileAmountsAndDatesAreProtected() {
        val input = """
            5.4 Currency
            Compensation is denominated in USD.
            6. Good Faith

            The Parties shall act in good faith.
            Monthly Compensation: 4250$ (max 12 Hours /week)
            Start Date: 08/03/2027
            Payment Method: Payslip
            The Independent Contractor shall provide remote game development services.
        """.trimIndent()
        val result = roundTrip(input)
        hidden(result, "4250", "08/03/2027")
        retained(result, "Good Faith\n\nThe Parties", "Monthly Compensation:", "max 12 Hours /week", "Payment Method: Payslip", "The Independent Contractor shall provide remote game development services")
    }

    @Test
    fun addressFragmentsAreProtectedOutsideTheirRepeatedLabelledField() {
        val result = roundTrip("Northlake Games Publishing, 4321 Cedar Bint Eastwood. 6084, Al Westhaven Dist. 12345 Lakeshore, Exampleland\nRegistered address: 4321 Cedar Bint Eastwood; provide a summary.")
        hidden(result, "4321", "Cedar", "Eastwood", "6084", "Westhaven", "12345", "Lakeshore", "Exampleland")
        retained(result, "Registered address:", "provide a summary")
    }

    @Test
    fun generalHealthTeachingPromptDoesNotBecomeAPersonalDisclosure() {
        val input = "Explain mental health for my class."
        assertEquals(input, roundTrip(input).text)
        hidden(roundTrip("Explain my diagnosis: anxiety. Please help me draft a note."), "anxiety")
    }

    @Test
    fun healthDisclosureKeepsPoliteAndSubjectElidedWritingRequests() {
        val polite = roundTrip("I have anxiety, so please help me write an extension request.")
        hidden(polite, "anxiety")
        retained(polite, "please help me write an extension request")
        val elided = roundTrip("I have anxiety and need help writing an extension request.")
        hidden(elided, "anxiety")
        retained(elided, "need help writing an extension request")
        listOf("Help me write a note about my anxiety.", "Draft a note about my anxiety.").forEach { input ->
            val result = roundTrip(input)
            hidden(result, "anxiety")
            retained(result, input.substringBefore("anxiety"))
        }
    }

    @Test
    fun addressFieldsDoNotConsumeFollowingRequestsOrSentences() {
        listOf(
            "My address is 10 Oak Road. Please draft a rental notice.",
            "My address is 10 Oak Rd. Please draft a rental notice.",
            "My address is 10 Oak Road, please draft a rental notice."
        ).forEach { input ->
            val result = roundTrip(input)
            hidden(result, "10 Oak")
            assertTrue(result.text.contains("please draft a rental notice", ignoreCase = true))
        }
        val statement = roundTrip("My address is 10 Oak Road. This is for a rental notice.")
        hidden(statement, "10 Oak")
        retained(statement, "This is for a rental notice")
        val abbreviation = roundTrip("Address: 10 St. James Road, Unit 8, Westhaven. Please draft a notice.")
        hidden(abbreviation, "10 St.", "James", "Unit 8", "Westhaven")
        retained(abbreviation, "Please draft a notice")
    }

    @Test
    fun ordinaryAddressVerbsDoNotBecomeLocationFields() {
        listOf(
            "Please address issues in the draft.",
            "Please address attachments before sending.",
            "Please address at-risk students in the report.",
            "Keep addressIs unchanged in the code.",
            "Keep addressAt unchanged in the code."
        ).forEach { input ->
            val result = roundTrip(input)
            assertEquals(input, result.text)
            assertTrue(result.receipt.ranges.isEmpty())
        }
    }

    @Test
    fun completeAddressSeparatorsStillProtectTheWholeField() {
        val address = "Unit 8, Westhaven"
        listOf("Home address:", "My address is", "Address at").forEach { label ->
            val input = "$label $address; please draft a notice."
            val result = roundTrip(input)
            hidden(result, address)
            retained(result, label, "; please draft a notice.")
            val start = input.indexOf(address)
            assertEquals(listOf(PrivacyProtectedRange(start, start + address.length)), result.receipt.ranges)
        }
    }

    @Test
    fun namedArtifactsDoNotBecomePeopleButExplicitPersonNamesDo() {
        listOf("Create a file named Report.", "File name: Report", "A variable named Result stores the total.").forEach { input ->
            assertEquals(input, roundTrip(input).text)
        }
        hidden(roundTrip("My friend named Mira will reply.\nName: Alex Rivera"), "Mira", "Alex", "Rivera")
    }

    @Test
    fun explicitLowerCaseFullNamesStayWholeWithoutConsumingTheirTask() {
        val field = roundTrip("Full name: mira elara voss\nPlease draft a short note.")
        hidden(field, "mira", "elara", "voss")
        retained(field, "Full name:", "Please draft a short note")
        val introduction = roundTrip("My name is mira voss. Please draft a short note.")
        hidden(introduction, "mira", "voss")
        retained(introduction, "My name is", "Please draft a short note")
        val joined = roundTrip("My name is mira voss and I need to write a short note.")
        hidden(joined, "mira", "voss")
        retained(joined, "and I need to write a short note")
        val unpunctuated = roundTrip("My name is mira voss please draft a short note.")
        hidden(unpunctuated, "mira", "voss")
        retained(unpunctuated, "please draft a short note")
        listOf("I am happy.", "Create a file named Report.", "Please draft a short note.", "Good Faith\nThe Parties").forEach { input ->
            assertEquals(input, roundTrip(input).text)
        }
    }

    @Test
    fun currencyPrefixSuffixIsoAndLocaleFormatsAreProtected() {
        listOf("$1,234.56", "4250$", "CAD 1 234,56", "1.234,56 EUR", "1\u202f234,56 €", "JPY 9000", "£ 42.50", "₹500").forEach { value ->
            val result = roundTrip("Amount: $value.")
            hidden(result, value)
            assertTrue(value, result.text.contains("_FINANCIAL_"))
            retained(result, "Amount:")
        }
    }

    @Test
    fun ordinalDatesSupportBothWordOrdersAndNumericForms() {
        listOf("March 8th", "8th March 2027", "8th of March", "2027-03-08", "08/03/2027").forEach { value ->
            val result = roundTrip("Appointment: $value.")
            hidden(result, value)
            retained(result, "Appointment:")
        }
    }

    @Test
    fun capitalizedHeadingsNeverBecomePeopleWithoutIdentityContext() {
        val input = "Good Faith\n\nThe Parties\nMonthly Compensation\nIndependent Contractor\nPayment Method"
        assertEquals(input, roundTrip(input).text)
    }

    @Test
    fun namedProjectsAreProtectedButProjectActionsAreRetained() {
        val result = roundTrip("Project: Aurora Dawn. Aurora Dawn is ready. I got the license fixed and the project done.")
        hidden(result, "Aurora Dawn")
        retained(result, "Project:", "is ready", "I got the license fixed and the project done")
    }

    @Test
    fun structuredDocumentIdentifiersDoNotConsumeAnOrdinaryVerb() {
        val result = roundTrip("My license number is not provided. Passport: XZ7654321. I got the license fixed.")
        hidden(result, "XZ7654321")
        retained(result, "My license number is not provided", "Passport:", "I got the license fixed")
    }

    @Test
    fun learnedPersonAliasesDoNotLeakFromEarlierHistoryOrHonorifics() {
        val mask = allCategories()
        val history = listOf("Dr. Rivera approved it. Alex can reply.", "From: Alex Rivera <alex@school.example>")
        mask.seedIdentities(history)
        val result = mask.protectWithReceipt(history.first())
        hidden(result, "Rivera", "Alex")
        retained(result, "Dr.", "approved it", "can reply")
    }

    @Test
    fun markerTypeTamperingCannotRestoreALocalValue() {
        val mask = allCategories()
        val marker = mask.protect("My name is Alex.").removePrefix("My name is ").removeSuffix(".")
        val restore = mask.sealWithReceipts()
        val tampered = marker.replace("_PERSON_", "_CONTACT_")
        assertEquals(tampered, restore(tampered).text)
        assertTrue(restore(tampered).receipt.ranges.isEmpty())
        assertEquals("Alex", restore(marker).text)
        assertEquals(setOf(marker), mask.issuedMarkers())
    }

    @Test
    fun publicChunksAndForeignNamespacesKeepEmptyContentBoundReceipts() {
        val mask = allCategories()
        val protected = mask.protectWithReceipt("My name is Mira Voss.")
        val foreign = allCategories().protect("My name is Dana Brooks.")
        val restore = mask.sealWithReceipts()
        listOf("", "A public answer chunk.", foreign).forEach { chunk ->
            val result = restore(chunk)
            assertEquals(chunk, result.text)
            assertTrue(result.receipt.ranges.isEmpty())
            assertTrue(result.receipt.validFor(chunk))
        }
        assertEquals("My name is Mira Voss.", restore(protected.text).text)
    }

    @Test
    fun summaryContainsOnlyCountsAndExplicitDetectedSpanCoverage() {
        val mask = allCategories()
        val input = "My partner Maya wants a beach trip."
        val result = mask.protectWithReceipt(input)
        val summary = mask.summary()
        assertEquals(1, summary.inspectedSurfaces)
        assertEquals(input.length.toLong(), summary.originalCharacters)
        assertEquals(result.text.length.toLong(), summary.transformedCharacters)
        assertEquals(4L, summary.protectedCharacters)
        assertEquals("DETECTED_SPANS_ONLY", summary.coverage)
        assertTrue(summary.matchesByCategory.getValue("IDENTITIES") > 0)
        assertFalse(summary.toString().contains("Maya"))
    }

    @Test
    fun largeCapitalizedPublicTextDoesNotOverflowOrBecomeAnIdentity() {
        val input = "Please summarize:\n" + "Overview ".repeat(2_048)
        assertEquals(input, roundTrip(input).text)
    }

    @Test
    fun veryLongExplicitNameIsProtectedAsAWholeWithoutRegexOverflow() {
        val name = List(2_048) { "mira" }.joinToString(" ")
        val result = roundTrip("Full name: $name\nPlease draft a note.")
        hidden(result, "mira")
        retained(result, "Full name:", "Please draft a note")
        assertEquals(1, result.receipt.ranges.size)
        assertEquals(name.length, result.receipt.ranges.single().endExclusive - result.receipt.ranges.single().start)
    }

    @Test
    fun familyHealthClausesStayPrivateWithoutConsumingRequestText() {
        val cases = listOf(
            Triple("My girlfriend is expecting a child. My brother needs care.", listOf("expecting", "needs care"), emptyList()),
            Triple("My partner needs personal care, but I want to ask for a schedule change.", listOf("personal care"), listOf("I want to ask for a schedule change")),
            Triple("Our sister is expecting twins; please draft a short leave request.", listOf("expecting twins"), listOf("please draft a short leave request")),
            Triple("Draft a note because my father requires nursing care.", listOf("nursing care"), listOf("Draft a note because")),
            Triple("My mother requires medical care today, so please draft a leave note.", listOf("medical care"), listOf("please draft a leave note"))
        )
        for ((input, privateValues, taskPhrases) in cases) {
            val mask = PrivateTextMask(setOf(PrivacyCategory.HEALTH), emptyList())
            val protected = mask.protectWithReceipt(input)
            hidden(protected, *privateValues.toTypedArray())
            retained(protected, *taskPhrases.toTypedArray())
            assertTrue(protected.receipt.validFor(input))
            assertTrue(protected.receipt.ranges.isNotEmpty())
            val restored = mask.sealWithReceipts()(protected.text)
            assertEquals(input, restored.text)
            assertTrue(restored.receipt.validFor(input))
        }
    }

    @Test
    fun ordinaryExpectationsAndCareLanguageStayPublic() {
        val publicInputs = listOf(
            "My girlfriend is expecting a package.",
            "My brother needs help with the printer.",
            "My car needs care.",
            "Our parser is expecting a child node.",
            "My child process needs care after the migration.",
            "My brother is expecting a child process to finish.",
            "My sister needs care instructions for a plant.",
            "My sister requires care instructions for a plant.",
            "My girlfriend and I need a reply.",
            "Teach how family caregiving works in general."
        )
        for (input in publicInputs) {
            val mask = PrivateTextMask(setOf(PrivacyCategory.HEALTH), emptyList())
            val protected = mask.protectWithReceipt(input)
            assertEquals(input, protected.text)
            assertTrue(protected.receipt.ranges.isEmpty())
            assertEquals(input, mask.sealWithReceipts()(protected.text).text)
        }
    }

    private fun allCategories() = PrivateTextMask(PrivacyCategory.entries.toSet(), emptyList())

    private fun roundTrip(input: String): PrivateProtectedText {
        val mask = allCategories()
        val protected = mask.protectWithReceipt(input)
        assertTrue(protected.receipt.validFor(input))
        val restored = mask.sealWithReceipts()(protected.text)
        assertEquals(input, restored.text)
        assertTrue(restored.receipt.validFor(restored.text))
        return protected
    }

    private fun hidden(result: PrivateProtectedText, vararg values: String) = values.forEach { value ->
        assertFalse("Sensitive value remained: $value", result.text.contains(value, ignoreCase = true))
    }

    private fun retained(result: PrivateProtectedText, vararg values: String) = values.forEach { value ->
        assertTrue("Task text was removed: $value", result.text.contains(value))
    }
}
