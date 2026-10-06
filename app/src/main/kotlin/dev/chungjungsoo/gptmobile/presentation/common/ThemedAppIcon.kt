package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.chungjungsoo.gptmobile.R

/** The launcher emblem, separated into palette layers so its chat/robot silhouette stays intact. */
@Composable
fun ThemedAppIcon(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.background(colors.primary, CircleShape).semantics { contentDescription = "GPT Mobile" }) {
        Image(painterResource(R.drawable.ic_app_emblem_bubble), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(colors.onPrimary))
        Image(painterResource(R.drawable.ic_app_emblem_face), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White))
        Image(painterResource(R.drawable.ic_app_emblem_details), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(colors.onSurface))
    }
}
