package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.health.connect.client.PermissionController
import com.example.data.HealthMetrics
import com.example.ui.theme.MyApplicationTheme
import android.graphics.RuntimeShader
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

const val SHADER = """
    uniform float2 resolution;
    uniform float time;
    uniform half4 color1;
    uniform half4 color2;
    uniform half4 color3;
    
    vec4 main(in vec2 fragCoord) {
        vec2 uv = fragCoord.xy / resolution.xy;
        
        float v1 = sin(uv.x * 5.0 + time) * 0.5 + 0.5;
        float v2 = sin(uv.y * 5.0 - time * 0.5) * 0.5 + 0.5;
        float v3 = sin((uv.x + uv.y) * 4.0 + time * 0.8) * 0.5 + 0.5;
        
        half4 mixedColor = mix(mix(color1, color2, v1), color3, v2 * v3);
        
        return mixedColor;
    }
"""

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.requestSync()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enforce pure transparent system backgrounds for the status and navigation bar
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0)
        )

        setContent {
            MyApplicationTheme {
                val healthData by viewModel.healthData.collectAsState()
                val context = LocalContext.current
                val view = LocalView.current
                val clipboardManager = LocalClipboardManager.current
                
                // Load/Save layout reordering custom profile
                val prefs = remember { context.getSharedPreferences("fitbit_lite_prefs", Context.MODE_PRIVATE) }
                var isReportSevenDays by remember { mutableStateOf(true) }
                var showStepsChartDetail by remember { mutableStateOf(false) }
                var showActiveMinsChartDetail by remember { mutableStateOf(false) }
                var showDistanceChartDetail by remember { mutableStateOf(false) }
                var showCaloriesChartDetail by remember { mutableStateOf(false) }
                var showSleepChartDetail by remember { mutableStateOf(false) }

                var cardOrder by remember {
                    val rawStr = prefs.getString("card_order", "sync,steps,active_min,distance,active_kcal,sleep_duration,weekly_chart,sleep_chart")
                        ?: "sync,steps,active_min,distance,active_kcal,sleep_duration,weekly_chart,sleep_chart"
                    val migratedStr = if (rawStr.contains("metrics")) {
                        rawStr.replace("metrics", "active_min,distance,active_kcal,sleep_duration")
                    } else {
                        rawStr
                    }
                    mutableStateOf(migratedStr.split(","))
                }

                var isReorderingMode by remember { mutableStateOf(false) }
                var showGoalDialog by remember { mutableStateOf(false) }
                var showSleepGoalDialog by remember { mutableStateOf(false) }
                var showSettingsMenu by remember { mutableStateOf(false) }
                var showReportCard by remember { mutableStateOf(false) }
                var showCaloriesOverlay by remember { mutableStateOf(false) }
                var showShareSelectionDialog by remember { mutableStateOf(false) }

                // Detect changes in syncing state to trigger successful sync haptics!
                var wasRefreshing by remember { mutableStateOf(false) }
                LaunchedEffect(healthData.isRefreshing) {
                    if (wasRefreshing && !healthData.isRefreshing) {
                        // refreshing completed!
                        if (healthData.isGoogleHealthSynced) {
                            try {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            } catch (e: Exception) {}
                        }
                    }
                    wasRefreshing = healthData.isRefreshing
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = PermissionController.createRequestPermissionResultContract()
                ) { granted ->
                    if (granted.isNotEmpty()) {
                        viewModel.requestSync()
                    }
                }

                // Collapsible top app bar setup
                @OptIn(ExperimentalMaterial3Api::class)
                val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
                
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        CenterAlignedTopAppBar(
                            scrollBehavior = scrollBehavior,
                            title = {
                                Text(
                                    text = "Fitbit Lite",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    letterSpacing = (-0.5).sp
                                )
                            },
                            actions = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            } catch (e: Exception) {}
                                            isReorderingMode = !isReorderingMode
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isReorderingMode) Icons.Default.CheckCircle else Icons.Default.Edit,
                                            contentDescription = "Reorder mode",
                                            tint = if (isReorderingMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    
                                    IconButton(
                                        onClick = {
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            } catch (e: Exception) {}
                                            showShareSelectionDialog = true
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share Report"
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            } catch (e: Exception) {}
                                            showSettingsMenu = true
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings Menu"
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
                            )
                        )
                    }
                ) { innerPadding ->
                    @OptIn(ExperimentalMaterial3Api::class)
                    val pullState = rememberPullToRefreshState()
                    @OptIn(ExperimentalMaterial3Api::class)
                    PullToRefreshBox(
                        isRefreshing = healthData.isRefreshing,
                        onRefresh = { 
                            try {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            } catch (e: Exception) {}
                            viewModel.requestSync() 
                        },
                        state = pullState,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pullState,
                                isRefreshing = healthData.isRefreshing,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = innerPadding.calculateTopPadding())
                            )
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
                            
                            if (isReorderingMode) {
                                Banner(
                                    title = "🎨 Reordering Mode Active",
                                    description = "Tap Up/Down buttons below any card to customize your home dashboard layout order. Tap check icon at top right to complete.",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            }

                            // Dynamic loading according to user customized cardOrder!
                            cardOrder.forEach { cardKey ->
                                when (cardKey) {
                                    "sync" -> {
                                        ReorderableWrapper(
                                            key = "sync",
                                            order = cardOrder,
                                            isReorderingMode = isReorderingMode,
                                            onOrderChange = { newOrder ->
                                                cardOrder = newOrder
                                                prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                            }
                                        ) {
                                            SyncHeaderStatus(
                                                isSynced = healthData.isGoogleHealthSynced,
                                                onSyncClick = {
                                                    try {
                                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                    } catch (e: Exception) {}
                                                    permissionLauncher.launch(viewModel.repository.permissions)
                                                }
                                            )
                                        }
                                    }
                                    "steps" -> {
                                        ReorderableWrapper(
                                            key = "steps",
                                            order = cardOrder,
                                            isReorderingMode = isReorderingMode,
                                            onOrderChange = { newOrder ->
                                                cardOrder = newOrder
                                                prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                            }
                                        ) {
                                            StepsCard(
                                                value = "${healthData.steps}",
                                                targetSteps = healthData.targetSteps,
                                                history = healthData.stepsHistory,
                                                onClick = {
                                                    try {
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    } catch (e: Exception) {}
                                                    showStepsChartDetail = true
                                                }
                                            )
                                        }
                                    }
                                    "active_min" -> {
                                        if (cardOrder.contains("distance")) {
                                            ReorderableWrapper(
                                                key = "active_min",
                                                order = cardOrder,
                                                isReorderingMode = isReorderingMode,
                                                onOrderChange = { newOrder ->
                                                    cardOrder = newOrder
                                                    prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                                }
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        SmallMetricCard(
                                                            title = "Active Min",
                                                            value = "${healthData.activeMinutes}",
                                                            unit = "min",
                                                            containerColor = Color(0xFFFFF1F0),
                                                            accentColor = Color(0xFFE04F34),
                                                            icon = Icons.AutoMirrored.Filled.DirectionsRun,
                                                            onClick = {
                                                                try {
                                                                     view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                                } catch (e: Exception) {}
                                                                showActiveMinsChartDetail = true
                                                            }
                                                        )
                                                    }
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        SmallMetricCard(
                                                            title = "Distance",
                                                            value = String.format("%.2f", healthData.distance / 1000.0),
                                                            unit = "km",
                                                            containerColor = Color(0xFFEFF6FF),
                                                            accentColor = Color(0xFF2563EB),
                                                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                                                            onClick = {
                                                                try {
                                                                     view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                                } catch (e: Exception) {}
                                                                showDistanceChartDetail = true
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            ReorderableWrapper(
                                                key = "active_min",
                                                order = cardOrder,
                                                isReorderingMode = isReorderingMode,
                                                onOrderChange = { newOrder ->
                                                    cardOrder = newOrder
                                                    prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                                }
                                            ) {
                                                SmallMetricCard(
                                                    title = "Active Min",
                                                    value = "${healthData.activeMinutes}",
                                                    unit = "min",
                                                    containerColor = Color(0xFFFFF1F0),
                                                    accentColor = Color(0xFFE04F34),
                                                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                                                    onClick = {
                                                        try {
                                                             view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                        } catch (e: Exception) {}
                                                        showActiveMinsChartDetail = true
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    "distance" -> {
                                        if (!cardOrder.contains("active_min")) {
                                            ReorderableWrapper(
                                                key = "distance",
                                                order = cardOrder,
                                                isReorderingMode = isReorderingMode,
                                                onOrderChange = { newOrder ->
                                                    cardOrder = newOrder
                                                    prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                                }
                                            ) {
                                                SmallMetricCard(
                                                    title = "Distance",
                                                    value = String.format("%.2f", healthData.distance / 1000.0),
                                                    unit = "km",
                                                    containerColor = Color(0xFFEFF6FF),
                                                    accentColor = Color(0xFF2563EB),
                                                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                                                    onClick = {
                                                        try {
                                                             view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                        } catch (e: Exception) {}
                                                        showDistanceChartDetail = true
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    "active_kcal" -> {
                                        ReorderableWrapper(
                                            key = "active_kcal",
                                            order = cardOrder,
                                            isReorderingMode = isReorderingMode,
                                            onOrderChange = { newOrder ->
                                                cardOrder = newOrder
                                                prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                            }
                                        ) {
                                            ConsolidatedCaloriesCard(
                                                activeCal = healthData.calories,
                                                basalCal = healthData.basalCalories,
                                                onClick = {
                                                    try {
                                                         view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                    } catch (e: Exception) {}
                                                    showCaloriesChartDetail = true
                                                }
                                            )
                                        }
                                    }
                                    "sleep_duration" -> {
                                        ReorderableWrapper(
                                            key = "sleep_duration",
                                            order = cardOrder,
                                            isReorderingMode = isReorderingMode,
                                            onOrderChange = { newOrder ->
                                                cardOrder = newOrder
                                                prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                            }
                                        ) {
                                            SleepDurationCard(
                                                sleepMinutes = healthData.sleepMinutes,
                                                onClick = {
                                                    try {
                                                         view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                    } catch (e: Exception) {}
                                                    showSleepChartDetail = true
                                                }
                                            )
                                        }
                                    }
                                    "weekly_chart" -> {
                                        ReorderableWrapper(
                                            key = "weekly_chart",
                                            order = cardOrder,
                                            isReorderingMode = isReorderingMode,
                                            onOrderChange = { newOrder ->
                                                cardOrder = newOrder
                                                prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                            }
                                        ) {
                                            WeeklyBarChart(
                                                data = healthData.stepsHistory,
                                                targetSteps = healthData.targetSteps,
                                                caloriesData = healthData.caloriesHistory,
                                                showCaloriesOverlay = showCaloriesOverlay,
                                                onToggleOverlay = {
                                                    try {
                                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                    } catch (e: Exception) {}
                                                    showCaloriesOverlay = !showCaloriesOverlay
                                                }
                                            )
                                        }
                                    }
                                    "sleep_chart" -> {
                                        ReorderableWrapper(
                                            key = "sleep_chart",
                                            order = cardOrder,
                                            isReorderingMode = isReorderingMode,
                                            onOrderChange = { newOrder ->
                                                cardOrder = newOrder
                                                prefs.edit().putString("card_order", newOrder.joinToString(",")).apply()
                                            }
                                        ) {
                                            SleepWeeklyLineChart(
                                                data = healthData.sleepHistory,
                                                efficiencyData = healthData.sleepEfficiencyHistory,
                                                modifier = Modifier.padding(bottom = if (isReorderingMode) 100.dp else 24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding()))
                        }
                    }
                }

                // 1b) Customize Sleep Target Goal Dialog Window
                if (showSleepGoalDialog) {
                    var innerSleepHours by remember { mutableStateOf(String.format("%.1f", healthData.targetSleepMinutes / 60f).replace(",", ".")) }
                    Dialog(onDismissRequest = { showSleepGoalDialog = false }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Nightlight,
                                    contentDescription = null,
                                    tint = Color(0xFF7C3AED), // Sleep purple
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Daily Sleep Goal Setting",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Specify your target sleep duration. Custom values align progress reports to optimize nocturnal recovery metrics.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))

                                // Fast Presets Row for Sleep
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(6.0, 7.0, 8.0, 9.0).forEach { preset ->
                                        val isSelected = innerSleepHours.toFloatOrNull() == preset.toFloat()
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isSelected) Color(0xFFFAF5FF)
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                )
                                                .border(
                                                    if (isSelected) BorderStroke(1.5.dp, Color(0xFF7C3AED))
                                                    else BorderStroke(0.dp, Color.Transparent),
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .clickable {
                                                    try {
                                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                    } catch (e: Exception) {}
                                                    innerSleepHours = String.format("%.1f", preset).replace(",", ".")
                                                }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${preset.toInt()}h",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = if (isSelected) Color(0xFF7C3AED)
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = innerSleepHours,
                                    onValueChange = { input ->
                                        val filtered = input.filter { c -> c.isDigit() || c == '.' }
                                        innerSleepHours = filtered
                                    },
                                    label = { Text("Sleep Target (hours)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedBorderColor = Color(0xFF7C3AED),
                                        focusedLabelColor = Color(0xFF7C3AED)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showSleepGoalDialog = false },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Cancel")
                                    }
                                    Button(
                                        onClick = {
                                            val hoursVal = innerSleepHours.toFloatOrNull() ?: 8.0f
                                            val minsVal = (hoursVal * 60).toInt().coerceAtLeast(30)
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            } catch (e: Exception) {}
                                            viewModel.updateTargetSleep(minsVal)
                                            showSleepGoalDialog = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF7C3AED),
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Text("Save")
                                    }
                                }
                            }
                        }
                    }
                }

                // 1) Customize Steps Target Goal Dialog Window
                if (showGoalDialog) {
                    var innerTarget by remember { mutableStateOf("${healthData.targetSteps}") }
                    Dialog(onDismissRequest = { showGoalDialog = false }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Daily Step Goal Setting",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Modify your local steps goal. Preset filters or custom entries immediately calibrate the tracker progress ring.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))

                                // Fast Presets Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(5000, 8000, 10000, 12000, 15000).forEach { preset ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (innerTarget == "$preset") MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                )
                                                .clickable {
                                                    try {
                                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                                    } catch (e: Exception) {}
                                                    innerTarget = "$preset"
                                                }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${preset / 1000}k",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = if (innerTarget == "$preset") MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = innerTarget,
                                    onValueChange = { innerTarget = it.filter { c -> c.isDigit() } },
                                    label = { Text("Step Target") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showGoalDialog = false },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Cancel")
                                    }
                                    Button(
                                        onClick = {
                                            val value = innerTarget.toIntOrNull() ?: 10000
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            } catch (e: Exception) {}
                                            viewModel.updateTargetSteps(value)
                                            showGoalDialog = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Save")
                                    }
                                }
                            }
                        }
                    }
                }

                // 2) Settings & Metrics Sandbox Editor Dialog Menu
                if (showSettingsMenu) {
                    Dialog(onDismissRequest = { showSettingsMenu = false }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.Start
                             ) {
                                Text(
                                    text = "Fitbit Lite Settings",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Configure targets and daily step goals to calibrate your local recovery metrics.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )

                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "GOALS & TARGETS",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        showGoalDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Set Daily Step Goal (${healthData.targetSteps})")
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        showSleepGoalDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Nightlight,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val hrs = healthData.targetSleepMinutes / 60
                                    val mins = healthData.targetSleepMinutes % 60
                                    Text("Set Sleep Goal (${hrs}h ${mins}m)")
                                }

                                Spacer(modifier = Modifier.height(24.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { showSettingsMenu = false }) {
                                        Text("Close")
                                    }
                                }
                            }
                        }
                    }
                }

                // 3) Beautiful Shareable Wrap Card Dialog (Spotify-Wrapped Visual style)
                if (showReportCard) {
                    val titleLabel = if (isReportSevenDays) "YOUR WEEK IN RECOVERY" else "YOUR DAY IN RECOVERY"
                    val subLabel = if (isReportSevenDays) "7-Day health Wrap" else "Today's summary Wrap"

                    val stepsTotalWrap = if (isReportSevenDays) {
                        healthData.stepsHistory.sum().toInt()
                    } else {
                        healthData.steps
                    }

                    val sleepMins = if (isReportSevenDays) {
                        (healthData.sleepHistory.sum() / healthData.sleepHistory.filter { it > 0 }.size.coerceAtLeast(1)).toInt()
                    } else {
                        healthData.sleepMinutes.toInt()
                    }
                    val sleepHrs = sleepMins / 60
                    val sleepMinsPart = sleepMins % 60

                    val caloriesTotalWrap = if (isReportSevenDays) {
                        healthData.caloriesHistory.sum() + (healthData.basalCalories * 7)
                    } else {
                        healthData.calories + healthData.basalCalories
                    }

                    val efficiencyAvgWrap = if (isReportSevenDays) {
                        (healthData.sleepEfficiencyHistory.sum() / healthData.sleepEfficiencyHistory.filter { it > 0 }.size.coerceAtLeast(1)).toInt()
                    } else {
                        (healthData.sleepEfficiencyHistory.lastOrNull() ?: 85f).toInt()
                    }

                    val activeMinutesTotal = if (isReportSevenDays) {
                        healthData.activeMinutes * 7
                    } else {
                        healthData.activeMinutes
                    }

                    Dialog(onDismissRequest = { showReportCard = false }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)) // High contrast dark cosmic background
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Fitbit Lite Wrap",
                                    color = Color(0xFF38BDF8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = titleLabel,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(modifier = Modifier.height(20.dp))

                                // Large glowing stats circular banner or simple stats wrapper
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(20.dp))
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Steps Total Block
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(Color(0xFF38BDF8).copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, tint = Color(0xFF38BDF8))
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(if (isReportSevenDays) "Total Steps" else "Steps Today", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                                            Text("${String.format("%,d", stepsTotalWrap)} steps", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    }

                                    // Sleep Avg Block
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(Color(0xFFA855F7).copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Nightlight, contentDescription = null, tint = Color(0xFFA855F7))
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(if (isReportSevenDays) "Average Sleep & Efficiency" else "Sleep Duration & Efficiency", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                                            Text("${sleepHrs}h ${sleepMinsPart}m @ ${efficiencyAvgWrap}% eff", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    }

                                    // Calories Wrap Block
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(Color(0xFFF97316).copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color(0xFFF97316))
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(if (isReportSevenDays) "Total Energy Burned" else "Calories Burned Today", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                                            Text("${String.format("%,.0f", caloriesTotalWrap)} kcal", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    }

                                    // Weekly Active Minutes Block
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(Color(0xFF22C55E).copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null, tint = Color(0xFF22C55E))
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(if (isReportSevenDays) "Weekly Active Minutes" else "Active Minutes Today", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                                            Text("${activeMinutesTotal} min logged", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showReportCard = false },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color.White
                                        ),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                        modifier = Modifier.weight(1.1f)
                                    ) {
                                        Text("Close")
                                    }

                                    Button(
                                        onClick = {
                                            val clipText = """
                                                🏆 Fitbit Lite $subLabel 🏆
                                                
                                                👟 Steps: ${String.format("%,d", stepsTotalWrap)} steps
                                                🌙 Sleep duration: ${sleepHrs}h ${sleepMinsPart}m 
                                                ✨ Recovery Efficiency: ${efficiencyAvgWrap}%
                                                🔥 Total Energy Burned: ${String.format("%,.0f", caloriesTotalWrap)} kcal
                                                ⚡ Active Minutes: ${activeMinutesTotal} min
                                                
                                                Calculated via Fitbit Lite Google Health integration.
                                            """.trimIndent()
                                            
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            } catch (e: Exception) {}

                                            clipboardManager.setText(AnnotatedString(clipText))
                                            Toast.makeText(context, "$subLabel copied!", Toast.LENGTH_SHORT).show()
                                         },
                                         shape = RoundedCornerShape(12.dp),
                                         colors = ButtonDefaults.buttonColors(
                                             containerColor = Color(0xFF38BDF8),
                                             contentColor = Color.Black
                                         ),
                                         modifier = Modifier.weight(1.4f)
                                     ) {
                                         Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                         Spacer(modifier = Modifier.width(4.dp))
                                         Text("Copy Wrapped")
                                     }
                                 }
                             }
                         }
                     }
                 }

                if (showShareSelectionDialog) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showShareSelectionDialog = false }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), // Deep cosmic slate
                            border = BorderStroke(2.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "FITBIT LITE SHARE",
                                    color = Color(0xFF38BDF8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Export Recovery Summary",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Generate and share visually polished stats in elegant Spotify-Wrapped themed formats.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                // Button/Card 1: Today's Summary Wrap
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            } catch (e: Exception) {}
                                            showShareSelectionDialog = false
                                            isReportSevenDays = false
                                            showReportCard = true
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.3f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(Color(0xFF38BDF8).copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Today, contentDescription = null, tint = Color(0xFF38BDF8))
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Today's Summary Wrap",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = "Stats logged today with recovery score",
                                                color = Color.White.copy(alpha = 0.6f),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Button/Card 2: 7-Day Health Wrap
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            } catch (e: Exception) {}
                                            showShareSelectionDialog = false
                                            isReportSevenDays = true
                                            showReportCard = true
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                    border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.3f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(Color(0xFFA855F7).copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.DateRange, contentDescription = null, tint = Color(0xFFA855F7))
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "7-Day Health Wrap",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = "Weekly recovery and historical achievements",
                                                color = Color.White.copy(alpha = 0.6f),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                TextButton(
                                    onClick = { showShareSelectionDialog = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Dismiss", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (showStepsChartDetail) {
                    ChartDetailDialog(
                        title = "Steps Tracked (7 Days)",
                        data = healthData.stepsHistory,
                        unit = "steps",
                        accentColor = MaterialTheme.colorScheme.primary,
                        onDismiss = { showStepsChartDetail = false }
                    )
                }
                if (showActiveMinsChartDetail) {
                    val computedActiveMinsHist = healthData.stepsHistory.map { (it / 150f).coerceAtLeast(0f) }
                    ChartDetailDialog(
                        title = "Active Minutes (7 Days)",
                        data = computedActiveMinsHist,
                        unit = "min",
                        accentColor = Color(0xFFE04F34),
                        onDismiss = { showActiveMinsChartDetail = false }
                    )
                }
                if (showDistanceChartDetail) {
                    val distanceHistKm = healthData.distanceHistory.map { it / 1000f }
                    ChartDetailDialog(
                        title = "Distance Traveled (7 Days)",
                        data = distanceHistKm,
                        unit = "km",
                        accentColor = Color(0xFF2563EB),
                        onDismiss = { showDistanceChartDetail = false }
                    )
                }
                if (showCaloriesChartDetail) {
                    ChartDetailDialog(
                        title = "Energy Burned (7 Days)",
                        data = healthData.caloriesHistory,
                        unit = "kcal",
                        accentColor = Color(0xFFEA580C),
                        onDismiss = { showCaloriesChartDetail = false }
                    )
                }
                if (showSleepChartDetail) {
                    val sleepHoursHist = healthData.sleepHistory.map { it / 60f }
                    ChartDetailDialog(
                        title = "Sleep Duration (7 Days)",
                        data = sleepHoursHist,
                        unit = "hours",
                        accentColor = Color(0xFF7C3AED),
                        onDismiss = { showSleepChartDetail = false }
                    )
                }
            }
        }
    }
}

@Composable
fun SyncHeaderStatus(
    isSynced: Boolean,
    onSyncClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSyncClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSynced) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isSynced) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.RotateRight,
                contentDescription = "Sync Status",
                tint = if (isSynced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSynced) "Synced with Google Health Connect" else "Google Health Offline",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSynced) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isSynced) "Metrics automatically sync in real-time." else "Tap to link Google Health for accurate aggregates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSynced) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun StepsCard(
    value: String,
    targetSteps: Int,
    history: List<Float>,
    onClick: () -> Unit
) {
    val time by produceState(0f) {
        while (true) {
            withFrameMillis {
                this.value = it / 1000f
            }
        }
    }
    
    val shader = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(SHADER)
        } else {
            null
        }
    }

    val stepsCount = value.toLongOrNull() ?: 0L
    val progressPercent = if (targetSteps > 0) ((stepsCount.toFloat() / targetSteps.toFloat()) * 100).coerceIn(0f, 100f) else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (shader != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            try {
                                shader.setFloatUniform("resolution", size.width, size.height)
                                shader.setFloatUniform("time", time)
                                val c1 = Color(0xFF673AB7)
                                val c2 = Color(0xFF3F51B5)
                                val c3 = Color(0xFF2196F3)
                                shader.setFloatUniform("color1", c1.red, c1.green, c1.blue, c1.alpha)
                                shader.setFloatUniform("color2", c2.red, c2.green, c2.blue, c2.alpha)
                                shader.setFloatUniform("color3", c3.red, c3.green, c3.blue, c3.alpha)
                                drawRect(brush = ShaderBrush(shader))
                            } catch (e: Throwable) {
                                drawRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF673AB7), Color(0xFF3949AB))
                                    )
                                )
                            }
                        }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF673AB7), Color(0xFF3949AB))
                            )
                        )
                )
            }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = "DAILY STEPS TRACKER",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.displayMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Goal: ${if (targetSteps >= 1000) "${targetSteps / 1000}k" else targetSteps}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${String.format("%.0f", progressPercent)}% Done",
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    LineChart(data = history, color = Color.White, strokeWidth = 3.2.dp)
                }
            }
        }
    }
}

@Composable
fun bannerBackground(): Brush {
    return Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f)
        )
    )
}

@Composable
fun Banner(
    title: String,
    description: String,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = 0.15f)),
        border = BorderStroke(1.dp, containerColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ReorderableWrapper(
    key: String,
    order: List<String>,
    isReorderingMode: Boolean,
    onOrderChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val idx = order.indexOf(key)
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isReorderingMode) {
                    Modifier
                        .padding(vertical = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f),
                            RoundedCornerShape(24.dp)
                        )
                        .border(
                            BorderStroke(
                                2.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            ),
                            RoundedCornerShape(24.dp)
                        )
                } else Modifier
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
            
            if (isReorderingMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Layout: ${idx + 1}/${order.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    
                    IconButton(
                        onClick = {
                            if (idx > 0) {
                                val newOrder = order.toMutableList()
                                val temp = newOrder[idx]
                                newOrder[idx] = newOrder[idx - 1]
                                newOrder[idx - 1] = temp
                                onOrderChange(newOrder)
                            }
                        },
                        enabled = idx > 0,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = {
                            if (idx < order.size - 1) {
                                val newOrder = order.toMutableList()
                                val temp = newOrder[idx]
                                newOrder[idx] = newOrder[idx + 1]
                                newOrder[idx + 1] = temp
                                onOrderChange(newOrder)
                            }
                        },
                        enabled = idx < order.size - 1,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                    }
                }
            }
        }
    }
}

@Composable
fun ConsolidatedCaloriesCard(
    activeCal: Double,
    basalCal: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalCal = activeCal + basalCal
    val isDark = isSystemInDarkTheme()
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "METABOLISM & ENERGY EXPENDITURE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = Color(0xFFEA580C)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = String.format("%.0f", totalCal),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = " total kcal today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isDark) Color(0xFFD97706).copy(alpha = 0.12f) else Color(0xFFFFFBEB),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD97706),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${String.format("%.0f", activeCal)} kcal",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Black
                    )
                }
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isDark) Color(0xFF16A34A).copy(alpha = 0.12f) else Color(0xFFF0FDF4),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = "BASAL (REST)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${String.format("%.0f", basalCal)} kcal",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
fun SleepDurationCard(
    sleepMinutes: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hrs = sleepMinutes / 60
    val mins = sleepMinutes % 60
    val isDark = isSystemInDarkTheme()
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isDark) Color(0xFF7C3AED).copy(alpha = 0.15f) else Color(0xFFFAF5FF),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Nightlight,
                    contentDescription = null,
                    tint = Color(0xFF7C3AED),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "REST & SLEEP STAGE METRICS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${hrs}h ${mins}m",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Optimal recovery duration logged today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun SmallMetricCard(
    title: String,
    value: String,
    unit: String,
    containerColor: Color,
    accentColor: Color,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val finalContainerColor = if (isDark) {
        accentColor.copy(alpha = 0.15f)
    } else {
        containerColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .height(112.dp),
        colors = CardDefaults.cardColors(containerColor = finalContainerColor),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) else Color(0xFF49454F).copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (isDark) MaterialTheme.colorScheme.onSurface else Color(0xFF1D1B20),
                    fontWeight = FontWeight.Black
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = " $unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF49454F),
                        modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun WeeklyBarChart(
    data: List<Float>,
    targetSteps: Int,
    caloriesData: List<Float>,
    showCaloriesOverlay: Boolean,
    onToggleOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val displayData = if (data.size >= 7) data.take(7) else (data + List(7 - data.size) { 0f })
    val displayCalories = if (caloriesData.size >= 7) caloriesData.take(7) else (caloriesData + List(7 - caloriesData.size) { 0f })
    
    var animationTriggered by remember { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(
        targetValue = if (animationTriggered) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "barScale"
    )
    
    LaunchedEffect(key1 = data) {
        animationTriggered = true
    }

    val isDark = isSystemInDarkTheme()
    val axisColor = if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f) else Color(0xFFF1F5F9)
    val barBgColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else Color(0xFFF3EDF7)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "WEEKLY ACTIVITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Text(
                        text = "Weekly steps statistics",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                
                // Compare with Active Calories Overlay pills selector
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onToggleOverlay() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (showCaloriesOverlay) Icons.Default.LocalFireDepartment else Icons.Default.Add,
                        contentDescription = "Overlay Calories",
                        tint = if (showCaloriesOverlay) Color(0xFFEA580C) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showCaloriesOverlay) "Hide Kcal" else "Compare Kcal",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (showCaloriesOverlay) Color(0xFFEA580C) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val maxVal = (displayData.maxOrNull() ?: 1f).coerceAtLeast(targetSteps.toFloat()).coerceAtLeast(1f)
                    val width = size.width
                    val height = size.height
                    
                    val gridLines = 4
                    val strokeSpacing = height / gridLines
                    for (i in 0..gridLines) {
                        val y = i * strokeSpacing
                        drawLine(
                            color = axisColor,
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    }
                    
                    val barCount = 7
                    val paddingFactor = 0.45f
                    val sectionWidth = width / barCount
                    val barWidth = sectionWidth * (1f - paddingFactor)
                    
                    displayData.forEachIndexed { idx, value ->
                        val barHeightFactor = (value / maxVal).coerceIn(0f, 1f)
                        val barHeight = height * barHeightFactor * animatedScale
                        val left = idx * sectionWidth + (sectionWidth * paddingFactor / 2f)
                        val top = height - barHeight
                        
                        val isOverTarget = value >= targetSteps
                        val barColor = if (isOverTarget) primaryColor else secondaryColor
                        
                        drawRoundRect(
                            color = barBgColor,
                            topLeft = Offset(left, 0f),
                            size = Size(barWidth, height),
                            cornerRadius = CornerRadius(12f, 12f)
                        )
                        
                        if (barHeight > 0f) {
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(left, top),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(12f, 12f)
                            )
                        }
                    }

                    // Dual scale calories history overlay curve!
                    if (showCaloriesOverlay) {
                        val maxCal = displayCalories.maxOrNull()?.coerceAtLeast(100f) ?: 100f
                        val path = Path()
                        val circlePoints = mutableListOf<Offset>()

                        displayCalories.forEachIndexed { index, calVal ->
                            val activeCalFactor = (calVal / maxCal).coerceIn(0f, 1f)
                            val sectionMidX = index * sectionWidth + (sectionWidth / 2f)
                            val lineY = height - (height * activeCalFactor * animatedScale)
                            
                            val pt = Offset(sectionMidX, lineY)
                            circlePoints.add(pt)
                            
                            if (index == 0) {
                                path.moveTo(pt.x, pt.y)
                            } else {
                                path.lineTo(pt.x, pt.y)
                            }
                        }

                        drawPath(
                            path = path,
                            color = Color(0xFFEA580C),
                            style = Stroke(
                                width = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        circlePoints.forEach { pt ->
                            drawCircle(
                                color = Color(0xFFEA580C),
                                radius = 4.dp.toPx(),
                                center = pt
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 2.dp.toPx(),
                                center = pt
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                days.forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun LineChart(data: List<Float>, color: Color, strokeWidth: androidx.compose.ui.unit.Dp = 4.dp) {
    if (data.isEmpty() || data.all { it == 0f }) {
        return
    }
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val maxVal = data.maxOrNull() ?: 10000f
        val minVal = 0f
        val range = maxVal - minVal
        
        val stepX = width / (data.size - 1).coerceAtLeast(1)
        val path = Path()
        
        data.forEachIndexed { i, value ->
            val x = i * stepX
            val y = height - ((value - minVal) / range * height)
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
fun SleepWeeklyLineChart(
    data: List<Float>,
    efficiencyData: List<Float>,
    modifier: Modifier = Modifier
) {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    
    val nonZeroCount = data.filter { it > 0f }.size.coerceAtLeast(1)
    val avgMinutes = (data.sum() / nonZeroCount).toInt()
    val avgHoursPart = avgMinutes / 60
    val avgMinsPart = avgMinutes % 60

    val nonZeroEffCount = efficiencyData.filter { it > 0f }.size.coerceAtLeast(1)
    val avgLabelEfficiency = (efficiencyData.sum() / nonZeroEffCount).toInt()

    var showEfficiencyMode by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Sleep & Efficiency (7 Days)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (showEfficiencyMode) "Avg Efficiency: ${avgLabelEfficiency}%" else "Avg Duration: ${avgHoursPart}h ${avgMinsPart}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
                
                // Toggle Duration vs Efficiency curve representation
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showEfficiencyMode = !showEfficiencyMode }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (showEfficiencyMode) Icons.Default.CheckCircle else Icons.Default.Nightlight,
                        contentDescription = "Toggle Sleep charts",
                        tint = if (showEfficiencyMode) Color(0xFF16A34A) else Color(0xFF7C3AED),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showEfficiencyMode) "Efficiency" else "Duration",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Canvas drawing
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(horizontal = 8.dp)
            ) {
                if (data.isEmpty() || data.all { it == 0f }) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No sleep records synchronized.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    val lineColor = if (showEfficiencyMode) Color(0xFF16A34A) else Color(0xFF7C3AED)
                    val gradientColor = lineColor.copy(alpha = 0.15f)
                    
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val activeSequence = if (showEfficiencyMode) efficiencyData else data.map { it / 60f }
                        val maxVal = if (showEfficiencyMode) 100f else activeSequence.maxOrNull()?.coerceAtLeast(8f) ?: 8f
                        val minVal = 0f
                        val range = maxVal - minVal

                        val width = size.width
                        val height = size.height

                        // Draw horizontal grid lines (e.g. 4h, 8h)
                        val lineCount = 4
                        for (i in 1..lineCount) {
                            val y = height - (i.toFloat() / lineCount.toFloat() * height)
                            drawLine(
                                color = Color.Gray.copy(alpha = 0.12f),
                                start = Offset(0f, y),
                                end = Offset(width, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        val stepX = width / (activeSequence.size - 1).coerceAtLeast(1)
                        val path = Path()
                        val fillPath = Path()

                        activeSequence.forEachIndexed { index, value ->
                            val x = index * stepX
                            val y = height - ((value - minVal) / range * height)
                            
                            if (index == 0) {
                                path.moveTo(x, y)
                                fillPath.moveTo(x, height)
                                fillPath.lineTo(x, y)
                            } else {
                                path.lineTo(x, y)
                                fillPath.lineTo(x, y)
                            }

                            if (index == activeSequence.size - 1) {
                                fillPath.lineTo(x, height)
                                fillPath.close()
                            }
                        }

                        // Draw gradient fill under the curve
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(gradientColor, Color.Transparent)
                            )
                        )

                        // Draw smooth outline path
                        drawPath(
                            path = path,
                            color = lineColor,
                            style = Stroke(
                                width = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // Draw data point dots
                        activeSequence.forEachIndexed { index, value ->
                            val x = index * stepX
                            val y = height - ((value - minVal) / range * height)
                            
                            drawCircle(
                                color = lineColor,
                                radius = 4.dp.toPx(),
                                center = Offset(x, y)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 2.dp.toPx(),
                                center = Offset(x, y)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                days.forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun MetricDetailChart(
    title: String,
    data: List<Float>,
    unit: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val displayData = if (data.size >= 7) data.take(7) else (data + List(7 - data.size) { 0f })
    val nonZeroCount = displayData.filter { it > 0f }.size.coerceAtLeast(1)
    val avg = displayData.sum() / nonZeroCount

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Avg: ${String.format("%.1f", avg)} $unit",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            ) {
                val maxVal = displayData.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    displayData.forEachIndexed { idx, valItem ->
                        val ratio = valItem / maxVal
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight(ratio.coerceIn(0.1f, 1.0f))
                                    .width(12.dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(accentColor)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = days[idx],
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChartDetailDialog(
    title: String,
    data: List<Float>,
    unit: String,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val displayData = if (data.size >= 7) data.take(7) else (data + List(7 - data.size) { 0f })
    val nonZeroCount = displayData.filter { it > 0f }.size.coerceAtLeast(1)
    val avg = displayData.sum() / nonZeroCount

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color = accentColor,
                        letterSpacing = 1.1.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stats Banner Inside the Dialog
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(accentColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "7-DAY AVERAGE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (unit == "steps" || unit == "kcal") {
                                String.format("%,.0f", avg)
                            } else {
                                String.format("%.1f", avg)
                            } + " $unit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "WEEKLY SUM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (unit == "steps" || unit == "kcal") {
                                String.format("%,.0f", displayData.sum())
                            } else {
                                String.format("%.1f", displayData.sum())
                            } + " $unit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Beautiful interactive-looking bar chart!
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    val maxVal = displayData.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        displayData.forEachIndexed { idx, valItem ->
                            val ratio = valItem / maxVal
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (valItem >= 1000f) {
                                        String.format("%.1fk", valItem / 1000f)
                                    } else {
                                        String.format("%.0f", valItem).replace(".0", "")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                // Fixed container for predictable bottom-aligned bar drawing
                                Box(
                                    modifier = Modifier
                                        .height(110.dp)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    val barHeight = (110.dp * ratio.coerceIn(0.08f, 1.0f))
                                    Box(
                                        modifier = Modifier
                                            .height(barHeight)
                                            .width(16.dp)
                                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                            .background(accentColor)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = days[idx],
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close Window", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
