package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette
import dev.chungjungsoo.gptmobile.data.dto.SavedThemeProfile
import dev.chungjungsoo.gptmobile.data.dto.ThemePresets
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog as Dialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.glassWindowBackdrop
import dev.chungjungsoo.gptmobile.presentation.theme.paletteContrastChecks
import dev.chungjungsoo.gptmobile.presentation.theme.resolveThemeColorScheme
import dev.chungjungsoo.gptmobile.presentation.theme.suggestPaletteAccent
import dev.chungjungsoo.gptmobile.presentation.ui.setting.appearance.AppearanceEditorViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.setting.appearance.parseThemeHex

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemeSettingsScreen(onDismiss: () -> Unit, asDialog: Boolean = true, initialTab: Int = 0) {
    val editor: AppearanceEditorViewModel = hiltViewModel()
    val state by editor.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab.takeIf { it in 0..3 } ?: 0) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var savedSearch by rememberSaveable { mutableStateOf("") }
    var picker by rememberSaveable { mutableStateOf<Int?>(null) }
    var save by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileAction by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var overflow by rememberSaveable { mutableStateOf(false) }
    var readability by rememberSaveable { mutableStateOf(false) }
    var suggestionUndo by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = state.draft
    val context = LocalContext.current
    val dark = when (draft.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val scheme = resolveThemeColorScheme(
        draft.dynamic,
        dark,
        draft.primary,
        draft.palette,
        if (draft.dynamic == DynamicTheme.ON) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    )
    val palette = draft.palette ?: CustomThemePalette(scheme.primary.toArgb().toLong(), scheme.secondary.toArgb().toLong(), scheme.background.toArgb().toLong(), scheme.surfaceContainerLow.toArgb().toLong())
    val checks = paletteContrastChecks(scheme)
    val selected = state.applied.savedProfiles.find { it.id == selectedId }
    val backup: SettingViewModelV2 = hiltViewModel()
    fun exit() {
        if (state.dirty) confirm = "leave" else onDismiss()
    }
    fun changePalette(value: CustomThemePalette) = editor.edit { it.copy(palette = value, primary = value.primary, dynamic = DynamicTheme.OFF) }
    fun select(profile: SavedThemeProfile) {
        // A different preset must not silently replace edited colours.
        if (state.dirty && draft.palette != null && (ThemePresets.profiles + state.applied.savedProfiles).none { it.palette == draft.palette && it.mode == draft.mode }) {
            selectedId = profile.id.ifBlank { "builtin:${profile.name}" }
            confirm = "replace"
        } else {
            editor.select(profile)
        }
    }
    BackHandler { exit() }
    val body: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.glassWindowBackdrop(),
            containerColor = dev.chungjungsoo.gptmobile.presentation.common.overlayWindowContainerColor(),
            topBar = {
                TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_appearance_3907f)) }, navigationIcon = {
                    IconButton(onClick = ::exit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }, actions = {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Rounded.MoreVert, "Appearance actions") }
                    DropdownMenu(overflow, { overflow = false }) {
                        DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_restore_defaults_602f8)) }, onClick = {
                            overflow = false
                            confirm = "defaults"
                        })
                    }
                })
            },
            bottomBar = {
                if (state.dirty) {
                    Surface(tonalElevation = 3.dp) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = editor::discard, enabled = !state.busy) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_discard_eb1a7)) }
                            Button(onClick = { if (checks.any { !it.passes }) confirm = "contrast" else editor.apply() }, enabled = !state.busy && !state.conflict) { Text(if (state.busy) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_090bd276d9) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_2265b1a2ad)) }
                        }
                    }
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                val active = if (state.applied.dynamicTheme == DynamicTheme.ON) {
                    "Wallpaper colours"
                } else {
                    (state.applied.savedProfiles + ThemePresets.profiles).find { it.id == state.applied.activeProfileId }?.name ?: if (state.applied.customPalette == null && state.applied.customPrimaryArgb == null) "Cyan" else "Custom"
                }
                Text(
                    if (!state.loaded) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_24351c73d1)
                    } else if (state.dirty) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6180617e84, (active).toString())
                    } else {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e120420698, (active).toString(), (state.applied.themeMode.name.lowercase()).toString())
                    },
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    listOf("Gallery", "Customize", "Display", "My Themes").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                if (state.loaded) {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
                        val wide = maxWidth >= 840.dp
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (wide) Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState()).padding(16.dp)) { AppearancePreview(scheme) }
                            LazyColumn(Modifier.weight(if (wide) 0.58f else 1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                                item {
                                    state.applied.recoveryWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    if (state.conflict) {
                                        SettingsPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_867ad70f96)) {
                                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_appearance_changed_elsewhere_your_draft_is_pr_80f6f))
                                            Row {
                                                TextButton(onClick = editor::discard) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_reload_bdc09)) }
                                                TextButton(onClick = editor::keepEditing) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_keep_editing_e76fd)) }
                                            }
                                        }
                                    }
                                    state.message?.let { message ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(message, Modifier.weight(1f))
                                            if (state.canUndo) TextButton(onClick = editor::undo) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_undo_a8283)) }
                                        }
                                    }
                                }
                                item {
                                    if (!wide && tab == 1) AppearancePreview(scheme)
                                }
                                when (tab) {
                                    0 -> {
                                        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All", "Dark", "Light").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) } } }
                                        item { Text("Find your atmosphere", style = MaterialTheme.typography.headlineSmall) }
                                        items(ThemePresets.profiles.filter { filter == "All" || it.mode.name.equals(filter, true) }.chunked(2), key = { it.first().id }) { pair ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                pair.forEach { profile -> ThemeProfileRow(profile, draft.activeProfileId == profile.id, Modifier.weight(1f), onClick = { select(profile) }) }
                                                if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                    1 -> {
                                        if (draft.dynamic == DynamicTheme.ON) {
                                            item {
                                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_wallpaper_colours_replace_the_custom_palette__b625f))
                                                TextButton(onClick = { editor.edit { it.copy(dynamic = DynamicTheme.OFF) } }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_use_custom_colours_99620)) }
                                            }
                                        } else {
                                            items(4) { role ->
                                                val values = listOf(palette.primary, palette.secondary, palette.background, palette.surface)
                                                Card(onClick = { picker = role }, modifier = Modifier.fillMaxWidth()) {
                                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                        Surface(color = Color(values[role]), modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.medium) {}
                                                        Column {
                                                            Text(listOf(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_808c2df826), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_fab1b919a1), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_7d67b6c9f3), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_966d9aaf31))[role])
                                                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_06x_a01e6).format(values[role] and 0xFFFFFF), style = MaterialTheme.typography.bodySmall)
                                                        }
                                                    }
                                                }
                                            }
                                            item {
                                                TextButton(onClick = { readability = !readability }) { Text(if (checks.all { it.passes }) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_a659977d52) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3fc7b61ec2, (checks.count { !it.passes }).toString())) }
                                                if (readability) checks.forEach { Text("${it.role}: %.2f · ${it.level}".format(it.ratio)) }
                                                if (checks.any { !it.passes }) {
                                                    TextButton(onClick = {
                                                        suggestionUndo = kotlinx.serialization.json.Json.encodeToString(CustomThemePalette.serializer(), palette)
                                                        changePalette(suggestPaletteAccent(palette))
                                                    }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_preview_suggested_correction_b117a)) }
                                                }
                                                if (suggestionUndo != null) {
                                                    TextButton(onClick = {
                                                        changePalette(kotlinx.serialization.json.Json.decodeFromString(CustomThemePalette.serializer(), suggestionUndo!!))
                                                        suggestionUndo = null
                                                    }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_undo_correction_89f4c)) }
                                                }
                                                TextButton(onClick = {
                                                    if (draft.palette == null) changePalette(palette)
                                                    name = ""
                                                    save = true
                                                }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_save_theme_a8dd5)) }
                                            }
                                        }
                                    }
                                    2 -> {
                                        item {
                                            Text("Menus and popups", style = MaterialTheme.typography.titleMedium)
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                listOf("glass", "solid").forEach { value ->
                                                    FilterChip(selected = draft.overlayMaterial == value, onClick = { editor.edit { it.copy(overlayMaterial = value) } }, label = { Text(value.replaceFirstChar { it.uppercase() }) })
                                                }
                                            }
                                            Text("Glass uses an 70% opaque theme background. Text and icons stay sharp.", style = MaterialTheme.typography.bodySmall)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Background blur", Modifier.weight(1f))
                                                Switch(checked = draft.overlayBlur == "auto", onCheckedChange = { enabled -> editor.edit { it.copy(overlayBlur = if (enabled) "auto" else "off") } })
                                            }
                                            Text("Strong 40 dp background blur. Turning this off keeps the theme tint; text and icons remain sharp.", style = MaterialTheme.typography.bodySmall)
                                            androidx.compose.runtime.CompositionLocalProvider(dev.chungjungsoo.gptmobile.presentation.common.LocalOverlayMaterial provides draft.overlayMaterial, dev.chungjungsoo.gptmobile.presentation.common.LocalOverlayBlur provides draft.overlayBlur) {
                                                dev.chungjungsoo.gptmobile.presentation.common.GlassMaterialPreview()
                                            }
                                        }
                                        item { AccessibilitySettingsPanel() }
                                        item { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_appearance_mode_3a6b5), style = MaterialTheme.typography.titleMedium) }
                                        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { mode -> FilterChip(selected = draft.mode == mode, onClick = { editor.edit { it.copy(mode = mode) } }, label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }) } } }
                                        item {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_wallpaper_colours_85997), Modifier.weight(1f))
                                                Switch(checked = draft.dynamic == DynamicTheme.ON, onCheckedChange = { enabled -> editor.edit { it.copy(dynamic = if (enabled) DynamicTheme.ON else DynamicTheme.OFF) } })
                                            }
                                            if (draft.dynamic == DynamicTheme.ON) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_wallpaper_colours_replace_the_custom_palette__b625f))
                                            if (draft.palette != null) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_custom_colours_stay_fixed_across_display_mode_5a2a4), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    3 -> {
                                        if (state.applied.savedProfiles.isEmpty()) {
                                            item {
                                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_no_saved_themes_yet_2794e), style = MaterialTheme.typography.titleMedium)
                                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_create_a_palette_and_save_it_to_keep_it_here_719f0))
                                            }
                                        }
                                        if (state.applied.savedProfiles.size > 6) item { OutlinedTextField(savedSearch, { savedSearch = it }, label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_search_saved_themes_c37c6)) }, modifier = Modifier.fillMaxWidth()) }
                                        items(state.applied.savedProfiles.filter { it.name.contains(savedSearch, true) }, key = { it.id }) { profile ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                ThemeProfileRow(profile, draft.activeProfileId == profile.id, Modifier.weight(1f), onClick = { select(profile) })
                                                IconButton(onClick = {
                                                    selectedId = profile.id
                                                    profileAction = "menu"
                                                }) { Icon(Icons.Rounded.MoreVert, "Manage ${profile.name}") }
                                            }
                                        }
                                        item {
                                            TextButton(onClick = { tab = 1 }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_create_a_theme_85173)) }
                                            TextButton(onClick = { if (state.dirty) confirm = "backup" else backup.openBackupRestoreDialog() }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_backup_restore_00769)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        BackupRestoreHost(backup)
    }
    if (asDialog) Dialog(onDismissRequest = ::exit, properties = DialogProperties(usePlatformDefaultWidth = false)) { body() } else body()
    picker?.let { role ->
        val values = listOf(palette.primary, palette.secondary, palette.background, palette.surface)
        ThemeColourPicker(listOf("Accent", "Secondary", "Background", "Cards")[role], values[role], { picker = null }) { value ->
            val next = values.toMutableList().apply { set(role, value) }
            changePalette(CustomThemePalette(next[0], next[1], next[2], next[3]))
            picker = null
        }
    }
    if (save || profileAction in listOf("Rename", "Duplicate")) {
        AlertDialog(
            onDismissRequest = {
                if (!state.busy) {
                    save = false
                    profileAction = ""
                }
            },
            title = { Text(if (save) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3bc197b2e5) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_db7baa203a, (profileAction).toString())) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_name_up_to_40_characters_89572)) })
                    state.message?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.busy && name.isNotBlank(), onClick = {
                    if (save) {
                        val match = state.applied.savedProfiles.find { it.name.equals(name.trim(), true) }
                        if (match != null) {
                            selectedId = match.id
                            confirm = "overwrite"
                        } else {
                            editor.save(name) { save = false }
                        }
                    } else {
                        selected?.let { editor.rename(it, name, profileAction == "Duplicate") { profileAction = "" } }
                    }
                }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_save_1509f)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    save = false
                    profileAction = ""
                }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_cancel_19766)) }
            }
        )
    }
    if (profileAction == "menu" && selected != null) {
        AlertDialog(onDismissRequest = { profileAction = "" }, title = { Text(selected.name) }, text = {
            Column {
                listOf("Rename", "Duplicate", "Delete").forEach { action ->
                    TextButton(onClick = {
                        profileAction = if (action == "Delete") "" else action
                        name = if (action == "Duplicate") "${selected.name} copy" else selected.name
                        if (action == "Delete") confirm = "delete"
                    }) { Text(action) }
                }
            }
        }, confirmButton = { TextButton(onClick = { profileAction = "" }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_close_7d9eb)) } })
    }
    if (confirm.isNotEmpty()) {
        AlertDialog(onDismissRequest = { confirm = "" }, title = {
            Text(
                when (confirm) {
                    "defaults" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6ebca908e8)
                    "delete" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5f60009001)
                    "overwrite" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0c36dc42c4)
                    "contrast" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_aa427990d7)
                    "replace" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_7b969bfbbb)
                    else -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0ea437b746)
                }
            )
        }, text = {
            Text(
                when (confirm) {
                    "defaults" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_8b4e0958bb)
                    "delete" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_bed083004e)
                    "overwrite" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_29a41ea4bb)
                    "contrast" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_dd9a1d362c)
                    "replace" -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e35bb2f885)
                    else -> androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6758710081)
                }
            )
        }, confirmButton = {
            TextButton(onClick = {
                when (confirm) {
                    "defaults" -> editor.defaults()
                    "delete" -> selected?.let(editor::delete)
                    "overwrite" -> editor.save(name, selectedId) { save = false }
                    "contrast" -> editor.apply()
                    "replace" -> (ThemePresets.profiles.find { it.id == selectedId } ?: selected)?.let(editor::select)
                    "backup" -> editor.apply { backup.openBackupRestoreDialog() }
                    else -> editor.apply(onDismiss)
                }
                confirm = ""
            }) {
                Text(
                    if (confirm == "contrast") {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4a4c808f00)
                    } else if (confirm in listOf(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_bae71d6eaf), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b5c415fee8))) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_417cf9c08b)
                    } else {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f66dc4fe25)
                    }
                )
            }
        }, dismissButton = {
            Row {
                if (confirm in listOf("leave", "backup")) {
                    TextButton(onClick = {
                        val target = confirm
                        editor.discard()
                        confirm = ""
                        if (target == "backup") backup.openBackupRestoreDialog() else onDismiss()
                    }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_discard_eb1a7)) }
                }
                TextButton(onClick = { confirm = "" }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_keep_editing_e76fd)) }
            }
        })
    }
}

@Composable
private fun ThemeProfileRow(profile: SavedThemeProfile, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = profile.palette
    val dark = profile.mode != ThemeMode.LIGHT
    val ink = if (dark) Color(0xFFF2F3F6) else Color(0xFF20242B)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    ) {
        Surface(color = Color(palette.background)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(palette.primary), shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(12.dp)) {}
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    if (selected) Icon(Icons.Rounded.Check, "Selected theme", tint = Color(palette.primary), modifier = Modifier.size(18.dp))
                }
                Surface(color = Color(palette.surface), shape = MaterialTheme.shapes.medium) {
                    Text("A new idea", color = ink, modifier = Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.labelMedium)
                }
                Text("Make it yours.", color = Color(palette.primary), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(palette.primary, palette.secondary, palette.surface).forEach { colour -> Surface(color = Color(colour), shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(16.dp)) {} }
                }
            }
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            Text(if (dark) "Dark · Calm contrast" else "Light · Clear & airy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeColourPicker(role: String, original: Long, onDismiss: () -> Unit, onUse: (Long) -> Unit) {
    var hex by rememberSaveable(role) { mutableStateOf("#%06X".format(original and 0xFFFFFF)) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val value = parseThemeHex(hex)
    fun dismiss() {
        if (value != ((original and 0xFFFFFF) or 0xFF000000)) discard = true else onDismiss()
    }
    AlertDialog(onDismissRequest = ::dismiss, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_cac1782751, role)) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(hex, { hex = it }, label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_hex_colour_68b4d)) }, isError = value == null, supportingText = { if (value == null) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_enter_exactly_six_hexadecimal_digits_for_exam_2236b)) })
            ColorWheelPicker(Color(value ?: original)) { hex = "#%06X".format(it.toArgb() and 0xFFFFFF) }
            listOf("Cyan" to 0xFF00FFDE, "Violet" to 0xFFCC00FF, "Coral" to 0xFFFF8A80, "Mint" to 0xFF80CBC4).forEach { (name, color) -> TextButton(onClick = { hex = "#%06X".format(color and 0xFFFFFF) }) { Text(name) } }
        }
    }, confirmButton = { TextButton(enabled = value != null, onClick = { value?.let(onUse) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_use_colour_ac738)) } }, dismissButton = { TextButton(onClick = ::dismiss) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_cancel_19766)) } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_discard_colour_changes_3d86a)) }, confirmButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_discard_eb1a7)) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_keep_editing_e76fd)) } })
}

@Composable
private fun AppearancePreview(scheme: androidx.compose.material3.ColorScheme) {
    MaterialTheme(colorScheme = scheme) {
        Surface(color = scheme.background, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_preview_324b1), style = MaterialTheme.typography.labelLarge)
                Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_help_me_explore_an_idea_a6c6e), Modifier.padding(12.dp)) }
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_a_clear_answer_with_a_useful_source_6a0f2), color = scheme.onBackground)
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_source_example_send_6eca0), color = scheme.primary)
            }
        }
    }
}
