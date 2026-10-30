package nl.beandesk.app.ui.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.beandesk.app.DutchRule
import nl.beandesk.app.data.remote.Contribution
import nl.beandesk.app.icon.AppIcon
import nl.beandesk.app.settings.LanguageChoice
import nl.beandesk.app.settings.ThemeChoice
import nl.beandesk.app.ui.theme.BeanDeskTheme
import nl.beandesk.core.model.Report
import nl.beandesk.core.model.WifiStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** The profile screen with given states; what it asks for is recorded instead of done. */
@RunWith(AndroidJUnit4::class)
class ProfileContentTest {
    @get:Rule(order = 0)
    val dutch = DutchRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val asked = mutableListOf<Any>()

    private fun show(state: ProfileUiState) {
        compose.setContent {
            BeanDeskTheme {
                ProfileContent(
                    state = state,
                    settings = SettingsState(ThemeChoice.SYSTEM, LanguageChoice.SYSTEM, AppIcon.ESPRESSO),
                    versionName = "1.2.3",
                    snackbarHostState = SnackbarHostState(),
                    onBack = { asked += "back" },
                    onRetry = { asked += "retry" },
                    onThemeChange = { asked += it },
                    onLanguageChange = { asked += it },
                    onAppIconChange = { asked += it },
                    onDeleteAccount = { asked += "delete" },
                )
            }
        }
    }

    private fun scrollTo(text: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))

    @Test
    fun yourReportsAndStarsWithWhatYouSaid() {
        show(
            ProfileUiState(
                isLoading = false,
                hasAccount = true,
                contributions = listOf(
                    Contribution("Koffie & Werk", Instant.parse("2026-10-28T10:12:00Z"), Report(placeId = 1, wifi = WifiStatus.FAST, quiet = true)),
                    Contribution("De Stille Hoek", Instant.parse("2026-10-20T09:00:00Z"), Report(placeId = 6, stars = 4)),
                    Contribution(null, Instant.parse("2026-10-01T09:00:00Z"), Report(placeId = 99, permanentlyClosed = true)),
                ),
            ),
        )
        compose.onNodeWithText("Je hielp bij 3 plekken. Dank je!").assertIsDisplayed()
        compose.onNodeWithText("Snelle wifi · Rustig").assertIsDisplayed()
        compose.onNodeWithText("4 sterren").assertIsDisplayed()
        compose.onNodeWithText("Plek die niet meer bestaat").assertIsDisplayed()
        compose.onNodeWithText("Permanent gesloten").assertIsDisplayed()
    }

    @Test
    fun deletingTheAccountAsksFirst() {
        show(ProfileUiState(isLoading = false, hasAccount = true))
        scrollTo("Account verwijderen")
        compose.onNodeWithText("Account verwijderen").performClick()
        compose.onNodeWithText("Account verwijderen?").assertIsDisplayed()
        compose.onNodeWithText("Annuleren").performClick()
        compose.onNodeWithText("Account verwijderen?").assertDoesNotExist()
        assertEquals(emptyList<Any>(), asked)

        compose.onNodeWithText("Account verwijderen").performClick()
        compose.onNodeWithText("Verwijderen").performClick()
        assertEquals(listOf<Any>("delete"), asked)
    }

    @Test
    fun withoutAnAccountThereIsNothingToDelete() {
        show(ProfileUiState(isLoading = false, hasAccount = false))
        compose.onNodeWithText("Je hebt nog niets gemeld", substring = true).assertIsDisplayed()
        scrollTo("Je hebt geen account")
        compose.onNodeWithText("Account verwijderen").assertDoesNotExist()
    }

    @Test
    fun sampleModeSaysEverythingStaysOnThePhone() {
        show(ProfileUiState(sendsReports = false, isLoading = false))
        compose.onNodeWithText("Voorbeeldmodus: meldingen blijven", substring = true).assertIsDisplayed()
        scrollTo("Voorbeeldmodus: er is geen account")
        compose.onNodeWithText("Account verwijderen").assertDoesNotExist()
    }

    @Test
    fun loadingFailedOffersToTryAgain() {
        show(ProfileUiState(isLoading = false, loadFailed = true))
        compose.onNodeWithText("Opnieuw").performClick()
        assertEquals(listOf<Any>("retry"), asked)
    }

    @Test
    fun choosingTheLookAndTheLanguage() {
        show(ProfileUiState(isLoading = false))
        scrollTo("Zoals je telefoon")
        compose.onNodeWithText("Zoals je telefoon").assertIsSelected()
        compose.onNodeWithText("Donker").performClick()
        scrollTo("English")
        compose.onNodeWithText("Taal van je telefoon").assertIsSelected()
        compose.onNodeWithText("English").performClick()
        // The language already in use is not chosen again.
        compose.onNodeWithText("Taal van je telefoon").performClick()
        assertEquals(listOf<Any>(ThemeChoice.DARK, LanguageChoice.ENGLISH), asked)
    }

    @Test
    fun thePrivacyStatementOpens() {
        show(ProfileUiState(isLoading = false))
        scrollTo("Privacyverklaring")
        compose.onNodeWithText("Privacyverklaring").performClick()
        compose.onNodeWithText("Je locatie").assertIsDisplayed()
        compose.onNodeWithText("Meldingen en sterren").assertIsDisplayed()
    }
}
