# UI Improvements for GPT_Mobile_AI-improved v0.9.5.0

## Summary of UI Design Improvements

This document outlines the UI enhancements developed and implemented for the GPT_Mobile_AI-improved application to improve user experience, visual design, gesture interactions, and accessibility.

## Implemented Features & Component Architecture

All components referenced below are fully implemented in the repository codebase.

### 1. AgentPlanCard (`app/src/main/kotlin/dev/chungjungsoo/gptmobile/ui/component/AgentPlanCard.kt`)
- **Visual Color-Coded Progress Indicators**: Dynamic `LinearProgressIndicator` showing proportional step completion (green for all successful, error theme when failed steps exist, primary brand during progress).
- **Enhanced Status Feedback**: Distinct icons, rotating execution spinners, and container background color feedback for all step execution states (`RUNNING`, `SUCCESS`, `FAILED`, `PENDING`, `SKIPPED`).
- **Improved Expand/Collapse Transitions**: Smooth vertical expansion animations (`expandVertically` / `shrinkVertically`) with clear visual cues and rotation state toggles.
- **Detailed Step Information**: Interactive per-step expansion displaying tool execution badges and monospace result snippets with custom borders.
- **Accessibility**: Semantic content descriptions, state descriptions (`Expanded`/`Collapsed` with step counters), and button roles.

### 2. SandboxedArtifactView (`app/src/main/kotlin/dev/chungjungsoo/gptmobile/ui/component/SandboxedArtifactView.kt`)
- **WebView Sandbox**: Isolated rendering environment with code and live preview toggle.
- **Security Isolation**: Strict network sandbox disabling file access, content access, and navigation overrides.

### 3. SwipeableChatRow & Conversation List (`app/src/main/kotlin/dev/chungjungsoo/gptmobile/ui/component/SwipeableChatRow.kt` & `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt`)
- **Swipe-to-Dismiss Gestures**:
  - Swipe right (`StartToEnd`) to archive with `primaryContainer` indicator and toast confirmation.
  - Swipe left (`EndToStart`) to delete with confirmation dialog.
- **1-Second Long-Press Action**: Long-press gesture detection (`pointerInput` + `tryAwaitRelease`) to toggle favorite/pin status with haptic feedback (`HapticFeedbackType.LongPress`).
- **Status Badges & Draft Indicators**:
  - Pinned status icon (`Icons.Filled.PushPin`).
  - Draft preview pills featuring italicized `DRAFT` badge with golden border and truncated text.

### 4. Message Bubble Design (`app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatBubble.kt`)
- **Message Separation**: Distinct user and assistant containers with custom corner shaping and dynamic theming.
- **Continuation Prompt Chips**: Visual continuation suggestion chips (`SuggestionChip`) with pulsing glow effect when responses can be continued.
- **Hierarchical Layout**: Unified presentation layer hosting thinking blocks, tool traces, telemetry diagnostics HUD, and interactive code blocks.

### 5. Tool Execution & Tracing (`app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ToolTraceBlock.kt`)
- **Contrast & Legibility**: Opacity tuning (`0.15f`) across light and dark theme palettes.
- **Trace Collapsibility**: Expandable and collapsible execution trace groups with execution status icons and duration metadata.
- **Tool Monograms & Badges**: Distinct visual monograms for GitHub (`GH`), Brave (`B`), Microsoft (`MS`), MCP (`M`), Web (`W`), and System (`SYS`).

### 6. Thinking Accordion (`app/src/main/kotlin/dev/chungjungsoo/gptmobile/ui/thinking/ThinkingAccordion.kt` & `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/thinking/ThinkingAccordion.kt`)
- **Reasoning Display**: Collapsible accordion tailored for reasoning models (DeepSeek R1, `<think>` blocks).
- **Streaming State Indicator**: Rotating execution indicator while thinking is in progress.
- **Smooth Animations**: Animated visibility (`expandVertically` / `shrinkVertically`) with italicized reasoning text styling.

---

## Accessibility & Design System Conformance

- **Contrast Ratios**: Verified Material 3 color container contrast across primary, surfaceVariant, and error palettes in light and dark themes.
- **Screen Reader Support**: Semantic content descriptions across all action icons, status indicators, and expandable card headers (`Modifier.semantics`).
- **Touch Target Sizing**: Minimum 48dp touch target adherence for interactive buttons and list actions.
- **Consistent Layout**: Predictable 8dp-aligned spacing and typography scale (`titleMedium`, `labelSmall`, monospace code snippets).
