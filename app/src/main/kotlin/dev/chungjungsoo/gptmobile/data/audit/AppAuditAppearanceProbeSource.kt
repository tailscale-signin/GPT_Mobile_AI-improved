package dev.chungjungsoo.gptmobile.data.audit

import androidx.compose.ui.graphics.Color
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport
import dev.chungjungsoo.gptmobile.presentation.ui.chat.GitHubPromptAnchor
import dev.chungjungsoo.gptmobile.presentation.ui.chat.GitHubScrollMode
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ProfileAttributionPalette
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ResponseSourceAppearance
import javax.inject.Inject

internal class AppAuditAppearanceProbeSource @Inject constructor() :
    AppAuditFixtureSource(
        "appearance",
        listOf(
            AppAuditFixture("response_origin_v1", "Gateway colour requires verified response origin", listOf("chat_ui", "gateway_client")) {
                verify(!ResponseSourceAppearance.isGateway(null))
                verify(!ResponseSourceAppearance.isGateway(ResponseSource(ResponseTransport.GATEWAY)))
                verify(!ResponseSourceAppearance.isGateway(ResponseSource(ResponseTransport.DIRECT, gatewayVerified = true)))
                verify(ResponseSourceAppearance.isGateway(ResponseSource(ResponseTransport.GATEWAY, gatewayVerified = true)))
            },
            AppAuditFixture("scroll_follow_v1", "Repeated history detachment and bottom arrival", listOf("chat_ui")) {
                val anchor = GitHubPromptAnchor(8f, GitHubScrollMode.FOLLOW_BOTTOM)
                repeat(32) {
                    anchor.move(12f)
                    verify(anchor.mode == GitHubScrollMode.USER_HISTORY)
                    anchor.endGesture()
                    verify(!anchor.shouldFollowAtBottom(true))
                    anchor.move(-12f)
                    anchor.endGesture()
                    verify(!anchor.shouldFollowAtBottom(false))
                    verify(anchor.shouldFollowAtBottom(true))
                    anchor.followBottom()
                    verify(anchor.mode == GitHubScrollMode.FOLLOW_BOTTOM)
                }
            },
            AppAuditFixture("theme_contrast_v1", "Gateway theme foreground contrast calculation", listOf("accessibility_and_localization", "chat_ui")) {
                for (surface in listOf(Color.White, Color.Black, Color(0xFF263238))) {
                    for (accent in listOf(Color(0xFFE0E0E0), Color(0x88008080))) {
                        val foreground = ResponseSourceAppearance.gatewayForeground(accent, surface)
                        verify(ProfileAttributionPalette.contrast(foreground, surface) >= 4.5)
                    }
                }
            }
        )
    )
