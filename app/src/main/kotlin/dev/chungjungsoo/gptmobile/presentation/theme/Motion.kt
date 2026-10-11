package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.presentation.common.LocalReducedMotion

@Composable
internal fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap() else spring(dampingRatio = 0.8f, stiffness = 380f)

@Composable
internal fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap() else spring(dampingRatio = 0.6f, stiffness = 800f)

@Composable
internal fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap() else spring(dampingRatio = 1f, stiffness = 3800f)
