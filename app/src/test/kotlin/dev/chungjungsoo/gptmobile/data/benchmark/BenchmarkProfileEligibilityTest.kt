package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkProfileEligibilityTest {
    @Test fun unselectedGatewayModelCannotEnterTheQueue() {
        val gateway = PlatformV2(name = "Gateway", compatibleType = ClientType.LLAMA, model = " \n ")
        assertNotNull(benchmarkProfileConfigurationIssue(gateway))
        assertNull(benchmarkProfileConfigurationIssue(gateway.copy(model = "selected-model")))
    }

    @Test fun snapshotEligibilityDoesNotRequireSecretsButAuthenticatedExecutionDoes() {
        val snapshot = PlatformV2(name = "Remote", model = "selected-model", compatibleType = ClientType.OPENROUTER, secretRef = "stored-key")
        assertNull(benchmarkProfileConfigurationIssue(snapshot))
        assertNotNull(benchmarkProfileConfigurationIssue(snapshot, checkCredentials = true))
        assertNull(benchmarkProfileConfigurationIssue(snapshot.copy(token = "resolved-key"), checkCredentials = true))
        assertNotNull(benchmarkProfileConfigurationIssue(snapshot.copy(enabled = false)))
    }

    @Test fun privateUnauthenticatedServersAndLocalRuntimesKeepTheirSupportedConfiguration() {
        for (provider in listOf(ClientType.LLAMA, ClientType.OLLAMA, ClientType.LITERT_LM)) {
            assertNull(benchmarkProfileConfigurationIssue(PlatformV2(name = "Private", model = "selected-model", compatibleType = provider), checkCredentials = true))
        }
    }
}
