package com.example.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.SubscriptionInfo
import com.example.data.repository.SubscriptionRepository
import com.example.vpn.subscription.SubscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class SubscriptionsUiState(
    val subscriptions: List<SubscriptionInfo> = emptyList(),
    val isSyncing: Boolean = false,
    val syncingSubscriptionId: String? = null,
    val showAddDialog: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class SubscriptionViewModel(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionsUiState())
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

    val subscriptionsList: StateFlow<List<SubscriptionInfo>> = subscriptionRepository.allSubscriptions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun showAddDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAddDialog = show, errorMessage = null)
    }

    fun addSubscription(name: String, url: String) {
        if (_uiState.value.isSyncing) return
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Subscription URL cannot be empty.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, showAddDialog = false)
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    subscriptionManager.addAndSyncSubscription(name, url)
                }
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    statusMessage = if (result.isSuccess) "Subscription added! Found ${result.totalFound} nodes (${result.addedCount} new, ${result.duplicateCount} duplicates)." else null,
                    errorMessage = if (!result.isSuccess) "Failed to sync subscription: ${result.errorMessage}" else null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    errorMessage = "Failed to add subscription: ${e.localizedMessage ?: "Unexpected error"}"
                )
            }
        }
    }

    fun syncSubscription(subscription: SubscriptionInfo) {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                syncingSubscriptionId = subscription.id,
                statusMessage = null,
                errorMessage = null
            )
            try {
                val result = subscriptionManager.syncSubscription(subscription)
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncingSubscriptionId = null,
                    statusMessage = if (result.isSuccess) "Updated ${subscription.name}: ${result.addedCount} new nodes added." else null,
                    errorMessage = if (!result.isSuccess) "Failed to update ${subscription.name}: ${result.errorMessage}" else null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncingSubscriptionId = null,
                    errorMessage = "Failed to update ${subscription.name}: ${e.localizedMessage ?: "Unexpected error"}"
                )
            }
        }
    }

    fun deleteSubscription(subscription: SubscriptionInfo) {
        viewModelScope.launch {
            try {
                subscriptionManager.deleteSubscriptionAndNodes(subscription)
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Deleted subscription '${subscription.name}' and nodes.",
                    errorMessage = null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to delete ${subscription.name}: ${e.localizedMessage ?: "Unexpected error"}"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
    }
}
