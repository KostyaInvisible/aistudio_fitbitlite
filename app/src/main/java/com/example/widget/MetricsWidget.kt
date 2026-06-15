package com.example.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.example.MainActivity

class MetricsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        
        // Load the actual real-time cached metrics from SharedPreferences
        val prefs = context.getSharedPreferences("fitbit_lite_prefs", Context.MODE_PRIVATE)
        val steps = prefs.getLong("local_steps", 4250L)
        val activeCalories = prefs.getFloat("local_calories", 180f)
        val testBasalCalories = prefs.getFloat("local_basal_calories", 1450f)
        val totalCalories = activeCalories + testBasalCalories
        val distance = prefs.getFloat("local_distance", 3200f)
        val sleepMinutes = prefs.getLong("local_sleep", 450L)

        val stepsText = String.format("%,d", steps)
        val kcalText = String.format("%.0f kcal", totalCalories)
        val distanceText = String.format("%.2f km", distance / 1000.0)
        val sleepText = "${sleepMinutes / 60}h ${sleepMinutes % 60}m"

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                // Hide header if height of the widget is small (less than 135dp)
                val showHeader = size.height >= 135.dp

                // Outer parent container with 12-petal/lobed cookie background image
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .clickable(actionStartActivity<MainActivity>()), // Clicking the widget opens the app
                    contentAlignment = Alignment.Center
                ) {
                    // 1) 12-lobed Material Design Scallop Cookie Background
                    Image(
                        provider = ImageProvider(com.example.R.drawable.bg_widget_cookie),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize()
                    )
                    
                    // 2) Content Column layered neatly over the background
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = if (showHeader) 20.dp else 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (showHeader) {
                            Text(
                                text = "Fitbit Lite Metrics",
                                style = TextStyle(
                                    color = GlanceTheme.colors.primary, // Clean brand accent tone
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                ),
                                modifier = GlanceModifier.padding(bottom = 8.dp)
                            )
                        }
                        
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            WidgetMetricBlock(
                                emoji = "👟",
                                label = "Steps",
                                value = stepsText,
                                accentColor = Color(0xFF60A5FA), // High contrast light blue
                                modifier = GlanceModifier.defaultWeight()
                            )
                            WidgetMetricBlock(
                                emoji = "🔥",
                                label = "Kcal",
                                value = kcalText,
                                accentColor = Color(0xFFFB923C), // High contrast light orange
                                modifier = GlanceModifier.defaultWeight()
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            WidgetMetricBlock(
                                emoji = "🏃",
                                label = "Distance",
                                value = distanceText,
                                accentColor = Color(0xFF4ADE80), // High contrast light green
                                modifier = GlanceModifier.defaultWeight()
                            )
                            WidgetMetricBlock(
                                emoji = "🌙",
                                label = "Sleep",
                                value = sleepText,
                                accentColor = Color(0xFFC084FC), // High contrast light purple
                                modifier = GlanceModifier.defaultWeight()
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun WidgetMetricBlock(
    emoji: String,
    label: String,
    value: String,
    accentColor: Color,
    modifier: GlanceModifier
) {
    Row(
        modifier = modifier.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circle background for the Emoji
        Box(
            modifier = GlanceModifier
                .size(28.dp)
                .background(ImageProvider(com.example.R.drawable.bg_widget_icon_circle)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                style = TextStyle(
                    fontSize = 16.sp
                )
            )
        }
        
        Spacer(modifier = GlanceModifier.width(6.dp))
        
        Column {
            Text(
                text = label,
                style = TextStyle(
                    color = androidx.glance.color.ColorProvider(day = accentColor, night = accentColor),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = value,
                style = TextStyle(
                    color = androidx.glance.color.ColorProvider(day = Color.White, night = Color.White),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
