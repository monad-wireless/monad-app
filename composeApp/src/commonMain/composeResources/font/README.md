# Custom Fonts

To add custom fonts to the application:

1. Place your font files in this directory
   - Example: `Roboto-Regular.ttf`, `Roboto-Bold.ttf`, etc.

2. Update `ui/theme/Type.kt` to use the fonts:

```kotlin
@OptIn(ExperimentalResourceApi::class)
@Composable
fun CustomFont() = FontFamily(
    Font(Res.font.Roboto_Regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.Roboto_Bold, FontWeight.Bold, FontStyle.Normal),
    Font(Res.font.Roboto_SemiBold, FontWeight.SemiBold, FontStyle.Normal),
    Font(Res.font.Roboto_Light, FontWeight.Light, FontStyle.Normal),
)
```

3. Rebuild the project to generate font resources

## Supported Font Formats
- TTF (TrueType Font)
- OTF (OpenType Font)

## Font Naming Convention
Use underscores instead of hyphens for file names:
- `Roboto-Regular.ttf` → Reference as `Res.font.Roboto_Regular`
- `OpenSans-Bold.ttf` → Reference as `Res.font.OpenSans_Bold`
