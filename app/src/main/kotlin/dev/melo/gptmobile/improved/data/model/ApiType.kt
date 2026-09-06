package dev.melo.gptmobile.improved.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class ApiType(
    val name: String,
    val models: List<String>,
    @param:StringRes val description: Int,
    @param:DrawableRes val icon: Int,
    @param:StringRes val helpUrl: Int
)
