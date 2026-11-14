package sh.hnet.calculator.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import sh.hnet.calculator.data.HistoryMode

/** Pages of the one back stack: the calculator at its root and what opens over it. */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Calculator : Screen
    @Serializable data class History(val mode: HistoryMode) : Screen
    @Serializable data object Settings : Screen
    @Serializable data object SettingsLanguage : Screen
    @Serializable data object SettingsReset : Screen
    @Serializable data object SettingsBackup : Screen
    @Serializable data object SettingsHistory : Screen
    @Serializable data object SettingsAbout : Screen
    @Serializable data object SettingsWhatsNew : Screen
    @Serializable data object SettingsLicense : Screen
    @Serializable data object SettingsLicenses : Screen
}
