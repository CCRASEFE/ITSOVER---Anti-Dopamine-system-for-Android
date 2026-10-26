package com.example.itsover02

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

data class BlockerUiState(
    val isRunning: Boolean = false
)

class MainViewModel : ViewModel() {
    val isRunning: StateFlow<Boolean> = LocalDnsBlockerService.isRunning
}