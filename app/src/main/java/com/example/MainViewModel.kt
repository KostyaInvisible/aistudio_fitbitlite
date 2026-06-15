package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.HealthRepository
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    val repository = HealthRepository(application)
    val healthData = repository.healthData

    init {
        viewModelScope.launch {
            repository.checkPermissionsAndFetch()
        }
    }

    fun requestSync() {
        viewModelScope.launch {
            repository.checkPermissionsAndFetch()
        }
    }

    fun generateSampleData() {
        viewModelScope.launch {
            repository.insertSampleData()
        }
    }

    fun updateTargetSteps(target: Int) {
        viewModelScope.launch {
            repository.setTargetSteps(target)
        }
    }

    fun updateTargetSleep(targetMins: Int) {
        viewModelScope.launch {
            repository.setTargetSleepMinutes(targetMins)
        }
    }

    fun updateSleep(mins: Long, efficiency: Float) {
        viewModelScope.launch {
            repository.updateLocalSleepMetrics(mins, efficiency)
        }
    }
}
