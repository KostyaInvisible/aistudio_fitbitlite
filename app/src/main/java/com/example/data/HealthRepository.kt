package com.example.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import com.example.widget.MetricsWidget
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class HealthMetrics(
    val steps: Long = 4250,
    val calories: Double = 180.0, // active kcal
    val basalCalories: Double = 1450.0, // basal kcal
    val distance: Double = 3200.0, // meters
    val sleepMinutes: Long = 450,
    val activeMinutes: Long = 45,
    val targetSteps: Int = 10000,
    val targetSleepMinutes: Int = 480,
    val stepsHistory: List<Float> = listOf(3200f, 5400f, 7100f, 4100f, 8300f, 6200f, 4250f),
    val caloriesHistory: List<Float> = listOf(140f, 210f, 280f, 160f, 320f, 240f, 180f),
    val distanceHistory: List<Float> = listOf(2400f, 4100f, 5300f, 3100f, 6200f, 4600f, 3200f),
    val sleepHistory: List<Float> = listOf(420f, 480f, 460f, 440f, 510f, 490f, 450f),
    val sleepEfficiencyHistory: List<Float> = listOf(78f, 85f, 82f, 74f, 88f, 81f, 80f),
    val isAvailable: Boolean = true,
    val isGoogleHealthSynced: Boolean = false,
    val isRefreshing: Boolean = false
)

class HealthRepository(private val context: Context) {
    
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    
    private val prefs = context.getSharedPreferences("fitbit_lite_prefs", Context.MODE_PRIVATE)

    private val client by lazy {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    private val _healthData = MutableStateFlow(HealthMetrics())
    val healthData: StateFlow<HealthMetrics> = _healthData

    init {
        // Initialize SharedPreferences defaults if not set yet
        if (!prefs.contains("target_steps")) {
            prefs.edit()
                .putInt("target_steps", 10000)
                .putLong("local_steps", 4250)
                .putFloat("local_calories", 180f)
                .putFloat("local_basal_calories", 1450f)
                .putFloat("local_distance", 3200f)
                .putLong("local_sleep", 450)
                .putLong("local_active_minutes", 45)
                .putString("local_steps_history", "3200,5400,7100,4100,8300,6200,4250")
                .putString("local_calories_history", "140,210,280,160,320,240,180")
                .putString("local_distance_history", "2400,4100,5300,3100,6200,4600,3200")
                .putString("local_sleep_history", "420,480,460,440,510,490,450")
                .putString("local_sleep_efficiency_history", "78,85,82,74,88,81,80")
                .apply()
        }
        updateStateWithLocalOrReal()
    }

    fun getTargetSteps(): Int {
        return prefs.getInt("target_steps", 10000)
    }

    fun setTargetSteps(target: Int) {
        prefs.edit().putInt("target_steps", target).apply()
        updateStateWithLocalOrReal()
    }

    fun getTargetSleepMinutes(): Int {
        return prefs.getInt("target_sleep_minutes", 480)
    }

    fun setTargetSleepMinutes(target: Int) {
        prefs.edit().putInt("target_sleep_minutes", target).apply()
        updateStateWithLocalOrReal()
    }

    private fun getLocalSteps(): Long {
        return prefs.getLong("local_steps", 4250L)
    }

    private fun getLocalCalories(): Double {
        return prefs.getFloat("local_calories", 180f).toDouble()
    }

    private fun getLocalBasalCalories(): Double {
        return prefs.getFloat("local_basal_calories", 1450f).toDouble()
    }

    private fun getLocalDistance(): Double {
        return prefs.getFloat("local_distance", 3200f).toDouble()
    }

    private fun getLocalSleep(): Long {
        return prefs.getLong("local_sleep", 450L)
    }

    private fun getLocalActiveMinutes(): Long {
        return prefs.getLong("local_active_minutes", 45L)
    }

    private fun getLocalStepsHistory(): List<Float> {
        val str = prefs.getString("local_steps_history", null) ?: "3200,5400,7100,4100,8300,6200,4250"
        return str.split(",").map { it.toFloatOrNull() ?: 0f }
    }

    private fun getLocalCaloriesHistory(): List<Float> {
        val str = prefs.getString("local_calories_history", null) ?: "140,210,280,160,320,240,180"
        return str.split(",").map { it.toFloatOrNull() ?: 0f }
    }

    private fun getLocalDistanceHistory(): List<Float> {
        val str = prefs.getString("local_distance_history", null) ?: "2400,4100,5300,3100,6200,4600,3200"
        return str.split(",").map { it.toFloatOrNull() ?: 0f }
    }

    private fun getLocalSleepHistory(): List<Float> {
        val str = prefs.getString("local_sleep_history", null) ?: "420,480,460,440,510,490,450"
        return str.split(",").map { it.toFloatOrNull() ?: 0f }
    }

    private fun getLocalSleepEfficiencyHistory(): List<Float> {
        val str = prefs.getString("local_sleep_efficiency_history", null) ?: "78,85,82,74,88,81,80"
        return str.split(",").map { it.toFloatOrNull() ?: 0f }
    }

    private fun isSyncedWithGoogle(): Boolean {
        return prefs.getBoolean("is_google_health_synced", false)
    }

    fun addLocalStepData(stepsToAdd: Long) {
        val currentSteps = getLocalSteps()
        val newSteps = currentSteps + stepsToAdd
        val newActiveMin = getLocalActiveMinutes() + (stepsToAdd / 100).coerceAtLeast(1)
        val newCal = getLocalCalories() + (stepsToAdd * 0.04)
        val newDist = getLocalDistance() + (stepsToAdd * 0.75)
        
        val history = getLocalStepsHistory().toMutableList()
        if (history.isNotEmpty()) {
            val lastIdx = history.size - 1
            history[lastIdx] = history[lastIdx] + stepsToAdd.toFloat()
        }
        
        val calHist = history.map { it * 0.04f }
        val distHist = history.map { it * 0.75f }
        val sleepHist = getLocalSleepHistory().toMutableList()
        if (sleepHist.isNotEmpty()) {
            val lastIdx = sleepHist.size - 1
            sleepHist[lastIdx] = getLocalSleep().toFloat()
        }
        
        persistMetrics(
            steps = newSteps,
            calories = newCal,
            basalCalories = getLocalBasalCalories(),
            distance = newDist,
            sleepMinutes = getLocalSleep(),
            activeMinutes = newActiveMin,
            stepsHist = history,
            calHist = calHist,
            distHist = distHist,
            sleepHist = sleepHist,
            sleepEffHist = getLocalSleepEfficiencyHistory(),
            isSynced = isSyncedWithGoogle()
        )
        
        updateStateWithLocalOrReal()
    }

    fun updateLocalSleepMetrics(newSleepMins: Long, newEfficiency: Float = 85f) {
        val sleepHist = getLocalSleepHistory().toMutableList()
        if (sleepHist.isNotEmpty()) {
            val lastIdx = sleepHist.size - 1
            sleepHist[lastIdx] = newSleepMins.toFloat()
        }
        val sleepEffHist = getLocalSleepEfficiencyHistory().toMutableList()
        if (sleepEffHist.isNotEmpty()) {
            val lastIdx = sleepEffHist.size - 1
            sleepEffHist[lastIdx] = newEfficiency
        }

        persistMetrics(
            steps = getLocalSteps(),
            calories = getLocalCalories(),
            basalCalories = getLocalBasalCalories(),
            distance = getLocalDistance(),
            sleepMinutes = newSleepMins,
            activeMinutes = getLocalActiveMinutes(),
            stepsHist = getLocalStepsHistory(),
            calHist = getLocalCaloriesHistory(),
            distHist = getLocalDistanceHistory(),
            sleepHist = sleepHist,
            sleepEffHist = sleepEffHist,
            isSynced = isSyncedWithGoogle()
        )
        updateStateWithLocalOrReal()
    }

    private fun persistMetrics(
        steps: Long,
        calories: Double,
        basalCalories: Double,
        distance: Double,
        sleepMinutes: Long,
        activeMinutes: Long,
        stepsHist: List<Float>,
        calHist: List<Float>,
        distHist: List<Float>,
        sleepHist: List<Float>,
        sleepEffHist: List<Float>,
        isSynced: Boolean
    ) {
        prefs.edit()
            .putLong("local_steps", steps)
            .putFloat("local_calories", calories.toFloat())
            .putFloat("local_basal_calories", basalCalories.toFloat())
            .putFloat("local_distance", distance.toFloat())
            .putLong("local_sleep", sleepMinutes)
            .putLong("local_active_minutes", activeMinutes)
            .putString("local_steps_history", stepsHist.joinToString(","))
            .putString("local_calories_history", calHist.joinToString(","))
            .putString("local_distance_history", distHist.joinToString(","))
            .putString("local_sleep_history", sleepHist.joinToString(","))
            .putString("local_sleep_efficiency_history", sleepEffHist.joinToString(","))
            .putBoolean("is_google_health_synced", isSynced)
            .apply()

        // Asynchronously update all glance appwidgets!
        try {
            repositoryScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    MetricsWidget().updateAll(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateStateWithLocalOrReal(
        realSteps: Long? = null,
        realCalories: Double? = null,
        realBasalCalories: Double? = null,
        realDistance: Double? = null,
        realSleepMinutes: Long? = null,
        realActiveMinutes: Long? = null,
        realStepsHistory: List<Float>? = null,
        realCaloriesHistory: List<Float>? = null,
        realDistanceHistory: List<Float>? = null,
        realSleepHistory: List<Float>? = null,
        realSleepEfficiencyHistory: List<Float>? = null,
        isSynced: Boolean = isSyncedWithGoogle(),
        isRefreshingStatus: Boolean? = null
    ) {
        val target = getTargetSteps()
        val targetSleep = getTargetSleepMinutes()
        val steps = realSteps ?: getLocalSteps()
        val cals = realCalories ?: getLocalCalories()
        val basal = realBasalCalories ?: getLocalBasalCalories()
        val dist = realDistance ?: getLocalDistance()
        val sleep = realSleepMinutes ?: getLocalSleep()
        val active = realActiveMinutes ?: getLocalActiveMinutes()
        
        val stepsHist = realStepsHistory ?: getLocalStepsHistory()
        val calHist = realCaloriesHistory ?: getLocalCaloriesHistory()
        val distHist = realDistanceHistory ?: getLocalDistanceHistory()
        val sleepHist = realSleepHistory ?: getLocalSleepHistory()
        val sleepEffHist = realSleepEfficiencyHistory ?: getLocalSleepEfficiencyHistory()
        
        _healthData.value = HealthMetrics(
            steps = steps,
            calories = cals,
            basalCalories = basal,
            distance = dist,
            sleepMinutes = sleep,
            activeMinutes = active,
            targetSteps = target,
            targetSleepMinutes = targetSleep,
            stepsHistory = stepsHist,
            caloriesHistory = calHist,
            distanceHistory = distHist,
            sleepHistory = sleepHist,
            sleepEfficiencyHistory = sleepEffHist,
            isAvailable = true,
            isGoogleHealthSynced = isSynced,
            isRefreshing = isRefreshingStatus ?: _healthData.value.isRefreshing
        )
    }

    suspend fun checkPermissionsAndFetch() {
        _healthData.value = _healthData.value.copy(isRefreshing = true)
        val clientInstance = client
        if (clientInstance == null) {
            updateStateWithLocalOrReal(isSynced = false, isRefreshingStatus = false)
            return
        }
        try {
            val granted = clientInstance.permissionController.getGrantedPermissions()
            val hasAnyPermission = permissions.any { granted.contains(it) }
            if (hasAnyPermission) {
                fetchHealthData()
            } else {
                updateStateWithLocalOrReal(isSynced = false, isRefreshingStatus = false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            updateStateWithLocalOrReal(isSynced = false, isRefreshingStatus = false)
        } finally {
            _healthData.value = _healthData.value.copy(isRefreshing = false)
        }
    }

    suspend fun fetchHealthData() {
        val clientInstance = client ?: return
        val end = ZonedDateTime.now()
        val start = end.truncatedTo(ChronoUnit.DAYS)

        try {
            val granted = clientInstance.permissionController.getGrantedPermissions()
            
            var stepsToday = getLocalSteps()
            var caloriesToday = getLocalCalories()
            var basalCaloriesToday = getLocalBasalCalories()
            var distanceToday = getLocalDistance()
            var sleepDuration = getLocalSleep()
            var activeMinutesToday = getLocalActiveMinutes()
            
            var stepsList = getLocalStepsHistory()
            var caloriesList = getLocalCaloriesHistory()
            var distanceList = getLocalDistanceHistory()
            var sleepList = getLocalSleepHistory()
            
            var isAnyRecordRead = false

            // Section 1: Read Steps
            if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
                try {
                    val stepsResponse = clientInstance.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(start.toInstant(), end.toInstant())
                        )
                    )
                    stepsToday = stepsResponse[StepsRecord.COUNT_TOTAL] ?: 0L
                    isAnyRecordRead = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Read Steps History for the past 7 days
                try {
                    val historyStart = end.minusDays(6).truncatedTo(ChronoUnit.DAYS)
                    val response = clientInstance.aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(historyStart.toInstant(), end.toInstant()),
                            timeRangeSlicer = Duration.ofDays(1)
                        )
                    )
                    val tempStepsList = MutableList(7) { 0f }
                    response.forEach { group ->
                        val dayOffset = ChronoUnit.DAYS.between(historyStart.toLocalDate(), group.startTime.atZone(end.zone).toLocalDate()).toInt()
                        if (dayOffset in 0..6) {
                            tempStepsList[dayOffset] = (group.result[StepsRecord.COUNT_TOTAL] ?: 0L).toFloat()
                        }
                    }
                    if (tempStepsList[6] < stepsToday) {
                        tempStepsList[6] = stepsToday.toFloat()
                    }
                    stepsList = tempStepsList.toList()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Section 2: Read Active Calories
            if (granted.contains(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class))) {
                try {
                    val caloriesResponse = clientInstance.aggregate(
                        AggregateRequest(
                            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(start.toInstant(), end.toInstant())
                        )
                    )
                    caloriesToday = caloriesResponse[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
                    isAnyRecordRead = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Read Active Calories History for the past 7 days
                try {
                    val historyStart = end.minusDays(6).truncatedTo(ChronoUnit.DAYS)
                    val response = clientInstance.aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(
                            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(historyStart.toInstant(), end.toInstant()),
                            timeRangeSlicer = Duration.ofDays(1)
                        )
                    )
                    val tempCaloriesList = MutableList(7) { 0f }
                    response.forEach { group ->
                        val dayOffset = ChronoUnit.DAYS.between(historyStart.toLocalDate(), group.startTime.atZone(end.zone).toLocalDate()).toInt()
                        if (dayOffset in 0..6) {
                            tempCaloriesList[dayOffset] = (group.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0).toFloat()
                        }
                    }
                    if (tempCaloriesList[6] < caloriesToday) {
                        tempCaloriesList[6] = caloriesToday.toFloat()
                    }
                    caloriesList = tempCaloriesList.toList()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Estimate/supplement active calories from steps if calories are missing or 0 but steps exist
            if (caloriesToday < 1.0 && stepsToday > 0) {
                caloriesToday = stepsToday * 0.04
            }
            val finalCaloriesList = caloriesList.toMutableList()
            for (i in finalCaloriesList.indices) {
                val cal = finalCaloriesList[i]
                val stepsValue = stepsList.getOrNull(i) ?: 0f
                if (cal < 1.0f && stepsValue > 0f) {
                    finalCaloriesList[i] = stepsValue * 0.04f
                }
            }
            caloriesList = finalCaloriesList.toList()

            // Section 2b: Read Basal Calories
            if (granted.contains(HealthPermission.getReadPermission(BasalMetabolicRateRecord::class))) {
                try {
                    val basalResponse = clientInstance.aggregate(
                        AggregateRequest(
                            metrics = setOf(BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(start.toInstant(), end.toInstant())
                        )
                    )
                    basalCaloriesToday = basalResponse[BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL]?.inKilocalories ?: getLocalBasalCalories()
                    isAnyRecordRead = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Section 3: Read Distance
            if (granted.contains(HealthPermission.getReadPermission(DistanceRecord::class))) {
                try {
                    val distanceResponse = clientInstance.aggregate(
                        AggregateRequest(
                            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(start.toInstant(), end.toInstant())
                        )
                    )
                    distanceToday = distanceResponse[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
                    isAnyRecordRead = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Read Distance History for the past 7 days
                try {
                    val historyStart = end.minusDays(6).truncatedTo(ChronoUnit.DAYS)
                    val response = clientInstance.aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(
                            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(historyStart.toInstant(), end.toInstant()),
                            timeRangeSlicer = Duration.ofDays(1)
                        )
                    )
                    val tempDistanceList = MutableList(7) { 0f }
                    response.forEach { group ->
                        val dayOffset = ChronoUnit.DAYS.between(historyStart.toLocalDate(), group.startTime.atZone(end.zone).toLocalDate()).toInt()
                        if (dayOffset in 0..6) {
                            tempDistanceList[dayOffset] = (group.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0).toFloat()
                        }
                    }
                    if (tempDistanceList[6] < distanceToday) {
                        tempDistanceList[6] = distanceToday.toFloat()
                    }
                    distanceList = tempDistanceList.toList()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            var sleepEffList = getLocalSleepEfficiencyHistory()

            // Section 4: Read Sleep
            if (granted.contains(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
                // Read Sleep History for the past 7 days to calculate both history and today's total sleep
                try {
                    val historyStart = end.minusDays(6).truncatedTo(ChronoUnit.DAYS)
                    val sleepRecords = clientInstance.readRecords(
                        ReadRecordsRequest(
                            SleepSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(historyStart.toInstant(), end.toInstant())
                        )
                    ).records

                    val tempSleepList = MutableList(7) { 0f }
                    val localEffs = getLocalSleepEfficiencyHistory()
                    val tempSleepEffList = MutableList(7) { idx -> localEffs.getOrNull(idx) ?: 82f }
                    
                    sleepRecords.forEach { record ->
                        val recordEndTime = record.endTime.atZone(end.zone)
                        val dayOffset = ChronoUnit.DAYS.between(historyStart.toLocalDate(), recordEndTime.toLocalDate()).toInt()
                        if (dayOffset in 0..6) {
                            val durationMins = ChronoUnit.MINUTES.between(record.startTime, record.endTime).toFloat()
                            tempSleepList[dayOffset] += durationMins
                            
                            val totalMs = ChronoUnit.MILLIS.between(record.startTime, record.endTime).toFloat().coerceAtLeast(1f)
                            val sleepMs = record.stages.filter { 
                                it.stage == SleepSessionRecord.STAGE_TYPE_DEEP ||
                                it.stage == SleepSessionRecord.STAGE_TYPE_LIGHT ||
                                it.stage == SleepSessionRecord.STAGE_TYPE_REM ||
                                it.stage == SleepSessionRecord.STAGE_TYPE_UNKNOWN
                            }.sumOf { ChronoUnit.MILLIS.between(it.startTime, it.endTime) }.toFloat()

                            var efficiency = 85f
                            val hrs = durationMins / 60f
                            if (sleepMs > 0f) {
                                val rawPercent = (sleepMs / totalMs * 100f).coerceIn(60f, 100f)
                                val optimalDiff = (hrs - 7.5f).coerceIn(-3f, 3f)
                                val durationFactor = -1.5f * (optimalDiff * optimalDiff)
                                val dayNoise = (((dayOffset * 7) % 11) - 5f)
                                efficiency = (rawPercent + durationFactor + dayNoise).coerceIn(68f, 96f)
                            } else {
                                if (hrs > 0f) {
                                    val baseEff = 85f
                                    val dev = hrs - 7.5f
                                    val noise = (((dayOffset * 7) % 11) - 5)
                                    val calculated = baseEff - (dev * dev * 2.2f) + noise
                                    calculated.coerceIn(68f, 95f)
                                } else {
                                    localEffs.getOrNull(dayOffset) ?: 80f
                                }
                            }
                            
                            val oldMins = tempSleepList[dayOffset] - durationMins
                            val oldEff = tempSleepEffList[dayOffset]
                            if (oldMins > 0f) {
                                tempSleepEffList[dayOffset] = ((oldEff * oldMins) + (efficiency * durationMins)) / tempSleepList[dayOffset]
                            } else {
                                tempSleepEffList[dayOffset] = efficiency
                            }
                        }
                    }
                    
                    sleepList = tempSleepList.toList()
                    sleepEffList = tempSleepEffList.toList()
                    
                    // Today's sleep duration is the sum of sessions ending today (index 6, end.toLocalDate())
                    sleepDuration = tempSleepList[6].toLong()
                    isAnyRecordRead = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Calculate active minutes based on steps today (standard guideline) if steps were read
            if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
                activeMinutesToday = stepsToday / 100
            }

            // Save state to SharedPreferences so it persists across launches
            persistMetrics(
                steps = stepsToday,
                calories = caloriesToday,
                basalCalories = basalCaloriesToday,
                distance = distanceToday,
                sleepMinutes = sleepDuration,
                activeMinutes = activeMinutesToday,
                stepsHist = stepsList,
                calHist = caloriesList,
                distHist = distanceList,
                sleepHist = sleepList,
                sleepEffHist = sleepEffList,
                isSynced = isAnyRecordRead
            )

            // Update state with actual Health Connect records
            updateStateWithLocalOrReal(
                realSteps = stepsToday,
                realCalories = caloriesToday,
                realBasalCalories = basalCaloriesToday,
                realDistance = distanceToday,
                realSleepMinutes = sleepDuration,
                realActiveMinutes = activeMinutesToday,
                realStepsHistory = stepsList,
                realCaloriesHistory = caloriesList,
                realDistanceHistory = distanceList,
                realSleepHistory = sleepList,
                realSleepEfficiencyHistory = sleepEffList,
                isSynced = isAnyRecordRead,
                isRefreshingStatus = false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            updateStateWithLocalOrReal(isRefreshingStatus = false)
        }
    }

    suspend fun insertSampleData() {
        // ALWAYS increment local/mock storage so both modes immediately reflect the addition
        addLocalStepData(2500L)
        
        val clientInstance = client ?: return
        val end = ZonedDateTime.now()
        val start = end.minusMinutes(30)
        
        try {
            val granted = clientInstance.permissionController.getGrantedPermissions()
            if (granted.contains(HealthPermission.getWritePermission(StepsRecord::class))) {
                val stepsRecord = StepsRecord(
                    count = 2500L,
                    startTime = start.toInstant(),
                    endTime = end.toInstant(),
                    startZoneOffset = start.offset,
                    endZoneOffset = end.offset
                )
                clientInstance.insertRecords(listOf(stepsRecord))
                fetchHealthData()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
