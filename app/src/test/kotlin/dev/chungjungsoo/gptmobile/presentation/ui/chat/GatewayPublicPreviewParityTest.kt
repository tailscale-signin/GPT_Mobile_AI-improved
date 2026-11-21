package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GitHubChatProgress
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayPublicPreviewParityTest {
    @Test fun desktopAndAndroidUseTheSamePublicPreviewContract() {
        val fixturePath = "gateway/desktop/native/tests/public-preview-v1.json"
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, fixturePath).isFile }
        val fixture = Json.parseToJsonElement(File(root, fixturePath).readText()).jsonObject
        val motion = fixture.getValue("motion").jsonObject
        fun expected(key: String) = motion.getValue(key).jsonPrimitive.int
        assertEquals(expected("entry_ms"), GITHUB_ACTIVE_PREVIEW_ENTRY_FADE_MILLIS)
        assertEquals(expected("refresh_ms"), GITHUB_ACTIVE_PREVIEW_REFRESH_FADE_MILLIS)
        assertEquals(expected("active_accent_ms"), GITHUB_PROGRESS_PAN_MILLIS)
        assertEquals(expected("change_interval_ms").toLong(), GitHubChatProgress.PREVIEW_INTERVAL_MS)
        assertEquals(expected("first_finding_ms").toLong(), GitHubChatProgress.summaryInterval(false))
        assertEquals(expected("later_finding_ms").toLong(), GitHubChatProgress.summaryInterval(true))
        fixture.getValue("reading").jsonArray.forEach { example ->
            val row = example.jsonObject
            val queue = GitHubLinePacingQueue()
            queue.append(row.getValue("lines").jsonPrimitive.int, 100f)
            assertEquals(row.getValue("duration_ms").jsonPrimitive.int.toLong(), queue.remainingMillis)
        }
    }
}
