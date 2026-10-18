package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Shared by navigation pops and the Settings Home tab. NavHost also seeks these on predictive back. */
internal fun backSlideFadeEnter(): EnterTransition =
    slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 10 } + fadeIn(tween(220))

internal fun backSlideFadeExit(): ExitTransition =
    slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(220))

internal fun forwardSlideFadeEnter(): EnterTransition =
    slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(220))

internal fun forwardSlideFadeExit(): ExitTransition =
    slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 12 } + fadeOut(tween(220))
