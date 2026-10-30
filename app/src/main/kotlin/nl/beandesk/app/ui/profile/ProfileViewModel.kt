package nl.beandesk.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.beandesk.app.BeanDeskApplication
import nl.beandesk.app.data.remote.Contribution
import nl.beandesk.app.data.remote.SupabaseBackend

data class ProfileUiState(
    // False with sample data: then nothing leaves the phone and there is no account.
    val sendsReports: Boolean = true,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val hasAccount: Boolean = false,
    // Newest first.
    val contributions: List<Contribution> = emptyList(),
    val isDeleting: Boolean = false,
)

enum class ProfileMessage { ACCOUNT_DELETED, DELETE_FAILED }

/** The user's own reports and stars, and deleting the account. [backend] is null with sample data. */
class ProfileViewModel(private val backend: SupabaseBackend?) : ViewModel() {
    private val mutableState = MutableStateFlow(ProfileUiState(sendsReports = backend != null, isLoading = backend != null))
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()

    private val messageChannel = Channel<ProfileMessage>(Channel.BUFFERED)
    val messages: Flow<ProfileMessage> = messageChannel.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        val backend = backend ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val hasAccount = backend.hasAccount()
                val contributions = if (hasAccount) backend.myContributions() else emptyList()
                mutableState.update { it.copy(isLoading = false, hasAccount = hasAccount, contributions = contributions) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the user's reports failed", e)
                mutableState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    fun deleteAccount() {
        val backend = backend ?: return
        if (mutableState.value.isDeleting) return
        viewModelScope.launch {
            mutableState.update { it.copy(isDeleting = true) }
            val message = try {
                backend.deleteAccount()
                mutableState.update { it.copy(hasAccount = false, contributions = emptyList(), loadFailed = false) }
                ProfileMessage.ACCOUNT_DELETED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Deleting the account failed", e)
                ProfileMessage.DELETE_FAILED
            }
            mutableState.update { it.copy(isDeleting = false) }
            messageChannel.send(message)
        }
    }

    companion object {
        private const val TAG = "ProfileViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BeanDeskApplication
                ProfileViewModel(app.container.backend)
            }
        }
    }
}
