package dev.melo.gptmobile.improved.presentation.ui.setup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.melo.gptmobile.improved.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupAppBar(
    modifier: Modifier = Modifier,
    backAction: () -> Unit
) {
    TopAppBar(
        title = {
            Text(text = stringResource(R.string.setup))
        },
        navigationIcon = {
            IconButton(onClick = backAction) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        },
        modifier = modifier
    )
}
