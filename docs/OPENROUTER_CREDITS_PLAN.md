# OpenRouter Credits Display Feature - Design & Implementation Plan

## 🎯 Overview

This document details the design, architecture, and implementation plan for the **OpenRouter Credits Display Box** in GPT Mobile AI. The component displays real-time remaining credits (`total_credits - total_usage`), credit usage percentages, and status indicators whenever OpenRouter is the active or configured AI platform.

---

## 🏗️ Architecture Design

```
┌─────────────────────────────────────────────────────────────┐
│                 Platform / Settings / Chat UI                │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│          Credits UI Component (`CreditsBox.kt`)             │
│  - Animated progress indicator                              │
│  - Glassmorphism / dynamic gradient style                   │
│  - Available / Low / No Credits status badges               │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│         Credits State Manager (`CreditsViewModel.kt`)       │
│  - Exposes `StateFlow<CreditsState>`                        │
│  - Triggers cached/fresh fetch operations                   │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│      Credits Service (`OpenRouterCreditsService.kt`)        │
│  - Consumes `GET https://openrouter.ai/api/v1/credits`      │
│  - Auth: `Bearer <management-key>`                          │
│  - Memory cache with configurable TTL (default: 5 min)      │
│  - Graceful error mapping (403, timeouts, network issues)   │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔌 API Endpoint Specifications

- **Endpoint**: `GET https://openrouter.ai/api/v1/credits`
- **Headers**:
  - `Authorization: Bearer <openrouter_management_key>`
  - `Accept: application/json`
- **Response Shape**:
```json
{
  "data": {
    "total_credits": 100.0,
    "total_usage": 25.5
  }
}
```
- **Computed Attributes**:
  - `remaining = max(0.0, total_credits - total_usage)`
  - `usage_percentage = if (total_credits > 0) (total_usage / total_credits) * 100 else 0`

---

## 🔒 Security & Key Management

- OpenRouter's `/api/v1/credits` endpoint strictly requires an OpenRouter **management key** (`sk-or-v1-...` with management permissions) rather than inference-only API keys.
- If the endpoint responds with `HTTP 403 Forbidden`, the service provides descriptive feedback: `"Management key required - regular API keys cannot fetch credits"`.
- API keys are retrieved securely from credential storage (`SecretRepository`) without logging sensitive token data.

---

## 🎨 UI & UX Specifications

- **Design Style**: Glassmorphism surface with dynamic linear gradient.
  - Normal state (`remaining > 10%`): Vibrant purple/indigo gradient (`#667EEA` → `#764BA2`).
  - Low credits state (`remaining <= 10%` or `< $0.50`): Warning coral/amber gradient (`#FF6B6B` → `#FF8E53`).
- **Progress Bar**:
  - Visual gauge representing `usage_percentage` with animated transition.
  - Color transitions from green (`< 70%`) to amber (`70-90%`) to red (`> 90%`).
- **States**:
  - `Loading`: Clean inline circular progress indicator.
  - `Success`: Formatted remaining balance (`$X.XX`), usage, and formatted last updated timestamp.
  - `Error`: Clear non-intrusive warning badge with retry action.
  - `Empty / Unconfigured`: Prompts user to configure OpenRouter credentials.

---

## 🗄️ Caching & Rate-Limiting Strategy

- In-memory cache holding the latest credits payload along with fetch timestamp.
- Default Cache TTL: 5 minutes (300,000 ms).
- Cache bypass supported via explicit user refresh button (`forceRefresh = true`).
- Network failures fall back to cached data when available.

---

## 📋 Implementation Roadmap

1. **Data Layer**:
   - `OpenRouterCredits.kt`: Models for deserializing OpenRouter API response and calculating remaining balance and percentage.
   - `OpenRouterCreditsService.kt`: Network service fetching credits with HTTP client and in-memory TTL caching.

2. **Presentation Layer**:
   - `CreditsBox.kt`: Jetpack Compose UI component implementing glassmorphic card, status indicators, and progress animation.
   - `CreditsViewModel.kt`: ViewModel coordinating fetch calls and exposing UI state.

3. **Dependency Injection & Integration**:
   - Register service in DI container.
   - Bind credits card to OpenRouter platform configuration in settings.
