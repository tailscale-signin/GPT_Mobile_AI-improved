package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccess
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterKeySnapshot
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterTab
import dev.chungjungsoo.gptmobile.data.openrouter.account.decimal
import dev.chungjungsoo.gptmobile.data.openrouter.account.perMillion
import dev.chungjungsoo.gptmobile.data.openrouter.account.usd
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterWorkspaceContractTest {
    private fun key(extra: String = "") = """{"data":{"label":"synthetic","usage":0,"is_free_tier":false,"is_management_key":false$extra}}"""

    @Test fun missingSchemaDoesNotUnlock() {
        listOf("{}", "{\"data\":{}}", "{\"data\":{\"label\":\"x\",\"usage\":0}}", key(",\"usage\":-2")).forEach { assertThrows(Exception::class.java) { OpenRouterKeySnapshot.parse(it) } }
    }

    @Test fun zeroAllowanceIsValidButExhausted() {
        val snapshot = OpenRouterKeySnapshot.parse(key(",\"limit_remaining\":0"))
        assertTrue(snapshot.exhausted)
        assertFalse(snapshot.isManagement)
        val access = OpenRouterAccess("revision")
        assertTrue(access.unlocked("revision"))
        assertEquals(OpenRouterTab.ACCOUNT, access.select(OpenRouterTab.ACCOUNT, "revision"))
    }

    @Test fun overdrawnAllowanceRemainsVisibleAndExhausted() {
        val snapshot = OpenRouterKeySnapshot.parse(key(",\"limit_remaining\":-0.001"))
        assertEquals("-0.001", snapshot.remaining)
        assertTrue(snapshot.exhausted)
        assertEquals(null, perMillion("-0.001"))
    }

    @Test fun revokedOrReplacedCredentialCannotRestoreProtectedTab() {
        assertEquals(OpenRouterTab.SETUP, OpenRouterAccess("a").select(OpenRouterTab.ACCOUNT, "b"))
        assertEquals(OpenRouterTab.SETUP, OpenRouterAccess().select(OpenRouterTab.ROUTING, null))
    }

    @Test fun managementAndProvisioningRolesAreRecognized() {
        assertTrue(OpenRouterKeySnapshot.parse(key(",\"is_management_key\":true")).isManagement)
        assertTrue(OpenRouterKeySnapshot.parse(key(",\"is_provisioning_key\":true")).isManagement)
    }

    @Test fun effectiveBudgetDoesNotAddOverlappingBudgets() {
        val snapshot = OpenRouterKeySnapshot.parse(key(",\"limit_remaining\":80,\"effective_budget\":{\"remaining_usd\":\"2.15\"}"))
        assertEquals("2.15", snapshot.remaining)
    }

    @Test fun endpointValidationRejectsSecretsRedirectOriginsAndInsecureUrls() {
        listOf("http://openrouter.ai/api/v1", "https://openrouter.ai.evil/api/v1", "https://key@openrouter.ai/api/v1", "https://openrouter.ai/api/v1?key=test", "https://openrouter.ai:444/api/v1").forEach { assertThrows(Exception::class.java) { OpenRouterIdentity.endpoint(it) } }
        assertEquals("https://eu.openrouter.ai/api/v1", OpenRouterIdentity.endpoint("https://eu.openrouter.ai/api/v1/"))
    }

    @Test fun controlsAndMultipleKeysAreRejectedInDraft() {
        listOf("a\nb", "a,b", "a b", "").forEach { assertThrows(Exception::class.java) { OpenRouterIdentity.credential(it) } }
        assertEquals("fixture", OpenRouterIdentity.credential(" fixture "))
    }

    @Test fun usdKeepsCurrencyUnderCanadianLocaleAndTinyPricesStayExact() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.CANADA)
            assertTrue(usd("0.00000001").contains("USD"))
            assertEquals("1.5", perMillion("0.0000015"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun stringAndNumericMoneyDecodeIdenticallyMissingIsNotZero() {
        assertEquals("1.25", Json.parseToJsonElement("\"1.25\"").decimal())
        assertEquals("1.25", Json.parseToJsonElement("1.25").decimal())
        assertNull(Json.parseToJsonElement("null").decimal())
        assertNull(JsonPrimitive(-1).decimal())
    }

    @Test fun rawProfileRoutingIsNotConfusedWithDefaultOptions() {
        val (_, routing) = OpenRouterPolicy.decodeProfile("""{"sort":"latency","only":["example"]}""")
        assertEquals("latency", routing?.sort)
        assertEquals(listOf("example"), routing?.only)
    }

    @Test fun profileOverridesConvenienceDefaultsButNeverWeakensRestrictions() {
        val policy = OpenRouterPolicy.resolve(
            OpenRouterProviderRouting(sort = "price", only = listOf("a", "b"), zdr = true, dataCollection = "deny", maxPrice = OpenRouterMaxPrice(prompt = JsonPrimitive(1))),
            OpenRouterProviderRouting(sort = "latency", only = listOf("b", "c"), zdr = false, dataCollection = "allow", maxPrice = OpenRouterMaxPrice(prompt = JsonPrimitive(2)))
        )
        assertEquals("latency", policy.sort)
        assertEquals(listOf("b"), policy.only)
        assertEquals(true, policy.zdr)
        assertEquals("deny", policy.dataCollection)
        assertEquals(JsonPrimitive(1), policy.maxPrice?.prompt)
    }

    @Test fun incompatibleRestrictionsFailClosed() {
        assertThrows(Exception::class.java) { OpenRouterPolicy.resolve(OpenRouterProviderRouting(only = listOf("a")), OpenRouterProviderRouting(only = listOf("b"))) }
        assertThrows(Exception::class.java) { OpenRouterPolicy.validate(OpenRouterProviderRouting(only = listOf("a"), ignore = listOf("a"))) }
    }

    @Test fun priceCeilingZeroIsSerializedAndInvalidPricesRejected() {
        val policy = OpenRouterProviderRouting(maxPrice = OpenRouterMaxPrice(prompt = JsonPrimitive(0)))
        OpenRouterPolicy.validate(policy)
        assertTrue(Json.encodeToString(policy).contains("\"prompt\":0"))
        assertThrows(Exception::class.java) { OpenRouterPolicy.validate(policy.copy(maxPrice = OpenRouterMaxPrice(prompt = JsonPrimitive(-1)))) }
        assertThrows(Exception::class.java) { OpenRouterPolicy.validate(policy.copy(maxPrice = OpenRouterMaxPrice(prompt = JsonPrimitive("invalid")))) }
    }

    @Test fun legacyZWeightIsQuarantinedAtTransmission() {
        assertFalse(Json.encodeToString(OpenRouterProviderRouting(zWeight = 2f).normalized()).contains("z_weight"))
    }

    @Test fun batchesRejectUnrepresentableRestrictions() {
        listOf(OpenRouterProviderRouting(zdr = true), OpenRouterProviderRouting(maxPrice = OpenRouterMaxPrice(prompt = JsonPrimitive(0))), OpenRouterProviderRouting(dataCollection = "deny")).forEach { assertThrows(Exception::class.java) { OpenRouterPolicy.batch(it, false) } }
        assertThrows(Exception::class.java) { OpenRouterPolicy.batch(OpenRouterProviderRouting(), true) }
        assertTrue(OpenRouterPolicy.batch(OpenRouterProviderRouting(only = listOf("a")), false).containsKey("only"))
    }

    @Test fun benchmarkAndToolCallsBypassRemoteCacheIncludingPresetDefaults() {
        val settings = OpenRouterSettings(apiKey = "", responseCachingEnabled = true, cacheTtlSeconds = 2, responseCacheTtlSeconds = 700)
        assertEquals("false", OpenRouterPolicy.headers(settings, true, false)["X-OpenRouter-Cache"])
        assertEquals("false", OpenRouterPolicy.headers(settings, false, true)["X-OpenRouter-Cache"])
        assertEquals("700", OpenRouterPolicy.headers(settings, false, false)["X-OpenRouter-Cache-TTL"])
    }

    @Test fun cacheAndSessionIdentitySeparateAccountsLanesAndPolicies() {
        assertNotEquals(OpenRouterPolicy.session("a", 1, "p", "reviewer"), OpenRouterPolicy.session("a", 1, "p", "primary"))
        assertNotEquals(OpenRouterPolicy.cacheIdentity("a", "e", "r", "m", "q", "p"), OpenRouterPolicy.cacheIdentity("b", "e", "r", "m", "q", "p"))
        assertNotEquals(OpenRouterIdentity.revision("a", "e", "key"), OpenRouterIdentity.revision("a", "e", "replacement"))
    }
}
