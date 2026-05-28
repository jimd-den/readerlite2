package com.example.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// We can define custom poster themes that alternate to engage the user's attention.
@Composable
fun TypographyPoster(
    sentence: String,
    subheading: String?,
    index: Int,
    modifier: Modifier = Modifier
) {
    // Select styling algorithmically based on sentence index to keep things visually exciting
    val styleOption = index % 4

    var animTrigger by remember(sentence) { mutableStateOf(false) }
    LaunchedEffect(sentence) {
        animTrigger = true
    }
    val alphaAnim by animateFloatAsState(
        targetValue = if (animTrigger) 1f else 0.2f,
        animationSpec = tween(durationMillis = 500),
        label = "fade"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = when (styleOption) {
                        0 -> listOf(Color(0xFF0F172A), Color(0xFF1E293B)) // Deep Slate Blue
                        1 -> listOf(Color(0xFF180A2B), Color(0xFF2C1654)) // Cosmic Indigo
                        2 -> listOf(Color(0xFF051D14), Color(0xFF0C3A2B)) // Forest Ink
                        else -> listOf(Color(0xFF1C1917), Color(0xFF2E2A24)) // Obsidian Sand
                    }
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(alphaAnim),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Label / Subheading Box
            if (!subheading.isNullOrEmpty()) {
                Text(
                    text = subheading.uppercase(),
                    color = when (styleOption) {
                        0 -> Color(0xFF38BDF8) // Sky Accent
                        1 -> Color(0xFFE9D5FF) // Lavender Accent
                        2 -> Color(0xFF34D399) // Mint Accent
                        else -> Color(0xFFFDE047) // Sand Accent
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // The Active Sentence Rendered using unique typography rules
            when (styleOption) {
                0 -> {
                    // Minimalist Editorial Serif
                    Text(
                        text = sentence,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.Serif,
                        lineHeight = 36.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                1 -> {
                    // Brutalist Tech Caps Accent
                    val annotatedText = sentence
                    Text(
                        text = annotatedText,
                        color = Color(0xFFFAF5FF),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif,
                        lineHeight = 32.sp,
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                2 -> {
                    // Modern Display Sans with line heights
                    Text(
                        text = sentence,
                        color = Color(0xFFECFDF5),
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.Default,
                        lineHeight = 34.sp,
                        textAlign = TextAlign.Justify,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                else -> {
                    // Clean Spaced Technical Sans-Serif
                    Text(
                        text = sentence,
                        color = Color(0xFFFFFDF5),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.SansSerif,
                        lineHeight = 33.sp,
                        textAlign = TextAlign.Left,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Footer metadata showing citation context
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SENTENCE ${index + 1}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            when (styleOption) {
                                0 -> Color(0xFF38BDF8)
                                1 -> Color(0xFFE9D5FF)
                                2 -> Color(0xFF34D399)
                                else -> Color(0xFFFDE047)
                            }
                        )
                )
            }
        }
    }
}
