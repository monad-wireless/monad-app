package sk.martinvanco.blarp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// TODO: Add custom fonts to composeResources/font directory
// Example: Roboto-Regular.ttf, Roboto-Bold.ttf, etc.
@Composable
fun CustomFont() = FontFamily.Default
    // Uncomment when fonts are added:
    /*
    FontFamily(
        Font(Res.font.YourFont_Regular, FontWeight.Normal, FontStyle.Normal),
        Font(Res.font.YourFont_Bold, FontWeight.Bold, FontStyle.Normal),
        Font(Res.font.YourFont_SemiBold, FontWeight.SemiBold, FontStyle.Normal),
        Font(Res.font.YourFont_Light, FontWeight.Light, FontStyle.Normal),
    )
    */

@Composable
fun AppTypography() = Typography(
    displayLarge = TextStyle(
        fontFamily = CustomFont(),
        fontSize = 28.sp,
        fontWeight = FontWeight(700),
        letterSpacing = 0.08.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = CustomFont(),
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    displaySmall = TextStyle(
        fontFamily = CustomFont(),
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontFamily = CustomFont(),
        fontWeight = FontWeight.Light,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = CustomFont(),
        fontWeight = FontWeight.Light,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = CustomFont(),
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    ),
    bodySmall = TextStyle(
        fontFamily = CustomFont(),
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
)
