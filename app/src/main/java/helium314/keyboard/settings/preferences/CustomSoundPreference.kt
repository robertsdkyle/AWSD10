// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import java.io.File

private const val MAX_SOUND_BYTES = 2 * 1024 * 1024
private const val MAX_SOUND_MS = 3000L

/** Lets the user pick an audio file for one key type ("key", "space", "delete" or "enter") of the custom sound pack. */
@Composable
fun CustomSoundPreference(setting: Setting, soundName: String) {
    val ctx = LocalContext.current
    val dir = AudioAndHapticFeedbackManager.getCustomSoundDir(ctx)
    val soundFile = File(dir, soundName)
    var changes by rememberSaveable { mutableIntStateOf(0) } // forces the description to refresh
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        dir.mkdirs()
        val tempFile = File(dir, "temp_$soundName")
        try {
            FileUtils.copyContentUriToNewFile(uri, ctx, tempFile)
            if (tempFile.length() > MAX_SOUND_BYTES) {
                error = R.string.custom_sound_too_big
                tempFile.delete()
                return@rememberLauncherForActivityResult
            }
            val durationMs = MediaMetadataRetriever().run {
                try {
                    setDataSource(tempFile.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    release()
                }
            } ?: throw IllegalArgumentException("not audio")
            if (durationMs > MAX_SOUND_MS) {
                error = R.string.custom_sound_too_long
                tempFile.delete()
                return@rememberLauncherForActivityResult
            }
            soundFile.delete()
            tempFile.renameTo(soundFile)
            AudioAndHapticFeedbackManager.getInstance().reloadSounds()
            changes++
        } catch (_: Exception) {
            error = R.string.file_read_error
            tempFile.delete()
        }
    }
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("audio/*")
    if (changes < 0) return // reading it makes the description below update after picking
    Preference(
        name = setting.title,
        description = stringResource(if (soundFile.exists()) R.string.custom_sound_set else R.string.custom_sound_not_set),
        onClick = {
            if (soundFile.exists()) showDialog = true
            else launcher.launch(intent)
        },
    )
    if (showDialog)
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { launcher.launch(intent) },
            onNeutral = {
                showDialog = false
                soundFile.delete()
                AudioAndHapticFeedbackManager.getInstance().reloadSounds()
                changes++
            },
            neutralButtonText = stringResource(R.string.delete),
            confirmButtonText = stringResource(R.string.load),
            title = { Text(setting.title) }
        )
    error?.let { InfoDialog(stringResource(it)) { error = null } }
}
