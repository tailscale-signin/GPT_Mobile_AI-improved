package dev.melo.gptmobile.improved.util

import dev.melo.gptmobile.improved.data.database.entity.PlatformV2

fun List<PlatformV2>.getPlatformName(uid: String): String = this.find { it.uid == uid }?.name ?: "Unknown"
