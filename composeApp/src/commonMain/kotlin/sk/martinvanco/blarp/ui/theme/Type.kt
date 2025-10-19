package sk.martinvanco.blarp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
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
    displayLarge = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 40.sp,
        fontWeight = FontWeight(700),
        letterSpacing = (-0.03).em,
    ),
    displayMedium = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    displaySmall = TextStyle(
        fontFamily = OnestFontFamily(),
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontFamily = OnestFontFamily(),
        fontWeight = FontWeight.Light,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = OnestFontFamily(),
        fontWeight = FontWeight.Light,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = OnestFontFamily(),
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    ),
    bodySmall = TextStyle(
        fontFamily = OnestFontFamily(),
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
)
