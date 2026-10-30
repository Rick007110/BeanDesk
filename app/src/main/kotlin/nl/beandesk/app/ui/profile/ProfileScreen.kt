package nl.beandesk.app.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.beandesk.app.R
import nl.beandesk.app.data.remote.Contribution
import nl.beandesk.app.icon.AppIcon
import nl.beandesk.app.settings.LanguageChoice
import nl.beandesk.app.settings.ThemeChoice
import nl.beandesk.app.ui.places.AppIconSheet
import nl.beandesk.app.ui.places.BottomSheet
import nl.beandesk.app.ui.places.dateText
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.Report
import nl.beandesk.core.model.WifiStatus

/** What the settings show: the choices as they are now. */
data class SettingsState(val theme: ThemeChoice, val language: LanguageChoice, val appIcon: AppIcon)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    state: ProfileUiState,
    settings: SettingsState,
    versionName: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onThemeChange: (ThemeChoice) -> Unit,
    onLanguageChange: (LanguageChoice) -> Unit,
    onAppIconChange: (AppIcon) -> Unit,
    onDeleteAccount: () -> Unit,
) {
    var showIconSheet by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            contributions(state, onRetry)

            item { SectionHeader(R.string.settings_title) }
            item {
                ChoiceGroup(
                    titleRes = R.string.settings_theme,
                    options = ThemeChoice.entries,
                    selected = settings.theme,
                    label = { stringResource(it.labelRes) },
                    onSelect = onThemeChange,
                )
            }
            item {
                ChoiceGroup(
                    titleRes = R.string.settings_language,
                    options = LanguageChoice.entries,
                    selected = settings.language,
                    label = { it.label() },
                    onSelect = { if (it != settings.language) onLanguageChange(it) },
                )
            }
            item {
                LinkRow(
                    title = stringResource(R.string.app_icon_title),
                    value = stringResource(settings.appIcon.labelRes),
                    onClick = { showIconSheet = true },
                    leading = {
                        Image(painterResource(settings.appIcon.badgeRes), contentDescription = null, modifier = Modifier.size(32.dp))
                    },
                )
            }

            item { SectionHeader(R.string.privacy_section) }
            item {
                LinkRow(
                    title = stringResource(R.string.privacy_title),
                    value = null,
                    onClick = { showPrivacy = true },
                )
            }
            item { AccountPart(state, onDelete = { confirmDelete = true }) }

            item { SectionHeader(R.string.about_title) }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.about_version, versionName), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.about_osm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showIconSheet) {
        AppIconSheet(selected = settings.appIcon, onSelect = onAppIconChange, onDismiss = { showIconSheet = false })
    }
    if (showPrivacy) PrivacySheet(onDismiss = { showPrivacy = false })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.account_delete_title)) },
            text = { Text(stringResource(R.string.account_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.account_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** "Jouw meldingen": what the user sent, newest first. */
private fun LazyListScope.contributions(state: ProfileUiState, onRetry: () -> Unit) {
    item { SectionHeader(R.string.contributions_title) }
    when {
        !state.sendsReports -> item { Hint(stringResource(R.string.contributions_sample_mode)) }
        state.isLoading -> item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
        }
        state.loadFailed -> item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.contributions_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        state.contributions.isEmpty() -> item { Hint(stringResource(R.string.contributions_none)) }
        else -> {
            item {
                val places = state.contributions.mapTo(HashSet()) { it.report.placeId }.size
                Hint(pluralStringResource(R.plurals.contributions_count, places, places))
            }
            items(state.contributions) { ContributionRow(it) }
        }
    }
}

@Composable
private fun ContributionRow(contribution: Contribution) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = contribution.placeName ?: stringResource(R.string.contribution_unknown_place),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = dateText(contribution.at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = reportSummary(contribution.report),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Snelle wifi · Rustig" or "4 sterren". */
@Composable
private fun reportSummary(report: Report): String = buildList {
    when (report.wifi) {
        WifiStatus.NONE -> add(stringResource(R.string.contribution_wifi_none))
        WifiStatus.YES -> add(stringResource(R.string.chip_wifi))
        WifiStatus.SLOW -> add(stringResource(R.string.chip_wifi_slow))
        WifiStatus.FAST -> add(stringResource(R.string.chip_wifi_fast))
        null -> Unit
    }
    when (report.power) {
        PowerLevel.NONE -> add(stringResource(R.string.contribution_power_none))
        PowerLevel.FEW -> add(stringResource(R.string.contribution_power_few))
        PowerLevel.MANY -> add(stringResource(R.string.chip_power_many))
        null -> Unit
    }
    when (report.quiet) {
        true -> add(stringResource(R.string.report_quiet))
        false -> add(stringResource(R.string.report_busy))
        null -> Unit
    }
    when (report.laptopPolicy) {
        LaptopPolicy.ALLOWED -> add(stringResource(R.string.chip_laptop_ok))
        LaptopPolicy.NOT_ON_WEEKENDS -> add(stringResource(R.string.chip_laptop_weekdays))
        LaptopPolicy.NOT_ALLOWED -> add(stringResource(R.string.chip_laptop_no))
        null -> Unit
    }
    if (report.permanentlyClosed) add(stringResource(R.string.contribution_closed))
    report.stars?.let { add(pluralStringResource(R.plurals.contribution_stars, it, it)) }
}.joinToString(" · ")

/** Deleting the account, or why there is none. */
@Composable
private fun AccountPart(state: ProfileUiState, onDelete: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !state.sendsReports -> Hint(stringResource(R.string.account_sample_mode), padded = false)
            state.hasAccount -> {
                Text(
                    text = stringResource(R.string.account_anonymous),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onDelete,
                    enabled = !state.isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    if (state.isDeleting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.account_delete))
                }
            }
            !state.isLoading -> Hint(stringResource(R.string.account_none), padded = false)
        }
    }
}

@Composable
private fun <T> ChoiceGroup(
    titleRes: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Column(Modifier.selectableGroup()) {
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton)
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    // The row handles the tap and reads out as one radio button.
                    RadioButton(selected = option == selected, onClick = null)
                    Spacer(Modifier.width(16.dp))
                    Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, value: String?, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(textRes: Int) {
    Column {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun Hint(text: String, padded: Boolean = true) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (padded) Modifier.padding(horizontal = 16.dp, vertical = 4.dp) else Modifier,
    )
}

/** What happens with data in BeanDesk, in plain words. */
@Composable
private fun PrivacySheet(onDismiss: () -> Unit) {
    BottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.privacy_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            PRIVACY_PARTS.forEach { (titleRes, bodyRes) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private val PRIVACY_PARTS = listOf(
    R.string.privacy_location_title to R.string.privacy_location_body,
    R.string.privacy_reports_title to R.string.privacy_reports_body,
    R.string.privacy_search_title to R.string.privacy_search_body,
    R.string.privacy_map_title to R.string.privacy_map_body,
    R.string.privacy_delete_title to R.string.privacy_delete_body,
)

private val ThemeChoice.labelRes: Int
    get() = when (this) {
        ThemeChoice.SYSTEM -> R.string.theme_system
        ThemeChoice.LIGHT -> R.string.theme_light
        ThemeChoice.DARK -> R.string.theme_dark
    }

/** Each language in its own words, so it can be found whatever the app shows now. */
@Composable
private fun LanguageChoice.label(): String = when (this) {
    LanguageChoice.SYSTEM -> stringResource(R.string.language_system)
    LanguageChoice.DUTCH -> "Nederlands"
    LanguageChoice.ENGLISH -> "English"
}
