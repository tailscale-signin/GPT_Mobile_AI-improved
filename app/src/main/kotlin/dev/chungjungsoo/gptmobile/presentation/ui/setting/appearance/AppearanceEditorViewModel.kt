package dev.chungjungsoo.gptmobile.presentation.ui.setting.appearance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette
import dev.chungjungsoo.gptmobile.data.dto.SavedThemeProfile
import dev.chungjungsoo.gptmobile.data.dto.ThemePresets
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppearanceDraft(
    val mode: ThemeMode,
    val dynamic: DynamicTheme,
    val primary: Long?,
    val palette: CustomThemePalette?,
    val activeProfileId: String? = null
) {
    fun applyTo(latest: ThemeSetting) = latest.copy(themeMode = mode, dynamicTheme = dynamic, customPrimaryArgb = primary, customPalette = palette, activeProfileId = activeProfileId)
    companion object {
        fun from(value: ThemeSetting) = AppearanceDraft(value.themeMode, value.dynamicTheme, value.customPrimaryArgb, value.customPalette, value.activeProfileId)
    }
}

data class AppearanceUiState(
    val applied: ThemeSetting = ThemeSetting(),
    val draft: AppearanceDraft = AppearanceDraft.from(ThemeSetting()),
    val loaded: Boolean = false,
    val canUndo: Boolean = false,
    val busy: Boolean = false,
    val conflict: Boolean = false,
    val message: String? = null
) {
    val dirty: Boolean get() = draft != AppearanceDraft.from(applied)
}

@HiltViewModel
class AppearanceEditorViewModel @Inject constructor(
    private val repository: SettingRepository,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val mutable = MutableStateFlow(AppearanceUiState())
    val state = mutable.asStateFlow()
    private var undo: (suspend () -> Unit)? = null

    init {
        viewModelScope.launch {
            repository.observeThemes().collect { latest ->
                val old = mutable.value
                val restored = if (!old.loaded) {
                    savedState.get<String>("appearanceDraft")?.let {
                        runCatching { Json.decodeFromString<AppearanceDraft>(it) }.getOrNull()
                    }
                } else {
                    null
                }
                mutable.value = old.copy(
                    applied = latest,
                    loaded = true,
                    draft = restored ?: if (!old.loaded || !old.dirty) AppearanceDraft.from(latest) else old.draft,
                    conflict = old.conflict || (old.loaded && old.dirty && AppearanceDraft.from(old.applied) != AppearanceDraft.from(latest))
                )
            }
        }
    }

    fun edit(transform: (AppearanceDraft) -> AppearanceDraft) {
        if (mutable.value.busy) return
        val old = mutable.value.draft
        val transformed = transform(old)
        val draft = if (transformed.activeProfileId == old.activeProfileId && transformed != old) transformed.copy(activeProfileId = "custom") else transformed
        savedState["appearanceDraft"] = Json.encodeToString(draft)
        mutable.value = mutable.value.copy(draft = draft, message = null)
    }
    fun select(profile: SavedThemeProfile) = edit { AppearanceDraft(profile.mode, DynamicTheme.OFF, profile.palette.primary, profile.palette, profile.id) }
    fun discard() {
        savedState.remove<String>("appearanceDraft")
        mutable.value = mutable.value.copy(draft = AppearanceDraft.from(mutable.value.applied), conflict = false, message = null)
    }
    fun keepEditing() {
        mutable.value = mutable.value.copy(conflict = false)
    }
    fun apply(onSuccess: () -> Unit = {}) = operation {
        val before = AppearanceDraft.from(mutable.value.applied)
        val draft = mutable.value.draft
        val applied = repository.mutateThemes { latest ->
            check(AppearanceDraft.from(latest) == before) { "Appearance changed elsewhere. Reload or keep editing before applying." }
            draft.applyTo(latest)
        }
        savedState.remove<String>("appearanceDraft")
        mutable.value = mutable.value.copy(applied = applied, draft = AppearanceDraft.from(applied), conflict = false, message = "Theme applied", canUndo = true)
        undo = {
            val restored = repository.mutateThemes { latest ->
                check(AppearanceDraft.from(latest) == AppearanceDraft.from(applied)) { "Appearance has since changed; Undo expired." }
                before.applyTo(latest)
            }
            mutable.value = mutable.value.copy(applied = restored)
            discard()
        }
        onSuccess()
    }
    fun defaults() {
        select(ThemePresets.defaultProfile)
        apply()
    }

    fun save(name: String, replaceId: String? = null, onSuccess: () -> Unit = {}) = operation {
        val label = validName(name)
        val draft = mutable.value.draft
        val palette = requireNotNull(draft.palette) { "Choose custom colours before saving a theme." }
        repository.mutateThemes { latest ->
            check(latest.savedProfiles.none { it.name.equals(label, true) && it.id != replaceId }) { "A theme with this name exists. Choose another name or explicitly replace it." }
            if (replaceId != null) check(latest.savedProfiles.any { it.id == replaceId }) { "The theme was removed. Save a new copy." }
            val profile = SavedThemeProfile(label, palette, draft.mode, replaceId ?: "saved:${UUID.randomUUID()}")
            latest.copy(savedProfiles = if (replaceId == null) latest.savedProfiles + profile else latest.savedProfiles.map { if (it.id == replaceId) profile else it })
        }
        mutable.value = mutable.value.copy(message = "Theme saved; appearance unchanged")
        onSuccess()
    }
    fun rename(profile: SavedThemeProfile, name: String, duplicate: Boolean, onSuccess: () -> Unit) = operation {
        val label = validName(name)
        repository.mutateThemes { latest ->
            check(latest.savedProfiles.none { it.name.equals(label, true) && (duplicate || it.id != profile.id) }) { "A theme with this name already exists." }
            val current = requireNotNull(latest.savedProfiles.find { it.id == profile.id }) { "Theme was removed." }
            check(current == profile) { "Theme changed elsewhere. Reopen this editor." }
            val next = current.copy(name = label, id = if (duplicate) "saved:${UUID.randomUUID()}" else current.id)
            latest.copy(savedProfiles = if (duplicate) latest.savedProfiles + next else latest.savedProfiles.map { if (it.id == profile.id) next else it })
        }
        mutable.value = mutable.value.copy(message = if (duplicate) "Theme duplicated" else "Theme renamed")
        onSuccess()
    }
    fun delete(profile: SavedThemeProfile) = operation {
        var index = 0
        repository.mutateThemes { latest ->
            index = latest.savedProfiles.indexOfFirst { it.id == profile.id }
            check(index >= 0 && latest.savedProfiles[index] == profile) { "Theme changed elsewhere. Reopen this editor." }
            latest.copy(savedProfiles = latest.savedProfiles.filterNot { it.id == profile.id })
        }
        undo = {
            repository.mutateThemes { latest ->
                check(latest.savedProfiles.none { it.id == profile.id || it.name.equals(profile.name, true) }) { "Library changed; Undo expired." }
                latest.copy(savedProfiles = latest.savedProfiles.toMutableList().apply { add(index.coerceAtMost(size), profile) })
            }
        }
        mutable.value = mutable.value.copy(message = "Theme deleted; applied colours retained", canUndo = true)
    }
    fun undo() = operation {
        val action = undo ?: return@operation
        action.invoke()
        undo = null
        mutable.value = mutable.value.copy(message = "Undone", canUndo = false)
    }
    fun clearMessage() {
        mutable.value = mutable.value.copy(message = null)
    }
    private fun operation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(message = error.message ?: "Could not save. Please try again.")
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
}

internal fun validName(name: String): String = name.trim().also {
    require(it.isNotBlank()) { "Enter a theme name." }
    require(it.codePointCount(0, it.length) <= 40) { "Use at most 40 characters." }
}

internal fun parseThemeHex(value: String): Long? = value.trim().removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)?.or(0xFF000000)
