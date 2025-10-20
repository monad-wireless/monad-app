package sk.martinvanco.blarp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import blarp_app.composeapp.generated.resources.Onest_Black
import blarp_app.composeapp.generated.resources.Onest_Bold
import blarp_app.composeapp.generated.resources.Onest_ExtraBold
import blarp_app.composeapp.generated.resources.Onest_ExtraLight
import blarp_app.composeapp.generated.resources.Onest_Light
import blarp_app.composeapp.generated.resources.Onest_Medium
import blarp_app.composeapp.generated.resources.Onest_Regular
import blarp_app.composeapp.generated.resources.Onest_SemiBold
import blarp_app.composeapp.generated.resources.Onest_Thin
import blarp_app.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font

@OptIn(ExperimentalResourceApi::class)
@Composable
fun OnestFontFamily() = FontFamily(
    Font(Res.font.Onest_Thin, FontWeight.Thin, FontStyle.Normal),
    Font(Res.font.Onest_ExtraLight, FontWeight.ExtraLight, FontStyle.Normal),
    Font(Res.font.Onest_Light, FontWeight.Light, FontStyle.Normal),
    Font(Res.font.Onest_Regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.Onest_Medium, FontWeight.Medium, FontStyle.Normal),
    Font(Res.font.Onest_SemiBold, FontWeight.SemiBold, FontStyle.Normal),
    Font(Res.font.Onest_Bold, FontWeight.Bold, FontStyle.Normal),
    Font(Res.font.Onest_ExtraBold, FontWeight.ExtraBold, FontStyle.Normal),
    Font(Res.font.Onest_Black, FontWeight.Black, FontStyle.Normal),
)

@Composable
fun AppTypography() = Typography(
    // H1
    headlineLarge = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
    ),
    // H2
    headlineMedium = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    // H3
    headlineSmall = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    // Text LG (20sp)
    titleLarge = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    // Text MD (18sp)
    titleMedium = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    // Text Regular (16sp)
    bodyLarge = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    // Text Small (14sp)
    bodyMedium = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
    ),
    // Text Tiny (12sp)
    bodySmall = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
    ),
    // Label variants
    labelLarge = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    ),
)

// Custom typography extension properties with semantic names
val Typography.h1: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = headlineLarge

val Typography.h2: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = headlineMedium

val Typography.h3: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = headlineSmall

val Typography.textLg: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = titleLarge

val Typography.textMd: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = titleMedium

val Typography.textRegular: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = bodyLarge

val Typography.textSmall: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = bodyMedium

val Typography.textTiny: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = bodySmall
