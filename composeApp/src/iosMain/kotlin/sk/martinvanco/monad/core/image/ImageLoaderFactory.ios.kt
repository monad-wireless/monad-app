package sk.martinvanco.monad.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.svg.SvgDecoder
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade

actual fun createImageLoader(context: PlatformContext): ImageLoader {
    return ImageLoader.Builder(context)
        .components {
            add(KtorNetworkFetcherFactory())
            // SVG, for the quest-step guidance plans. Registered on both platforms
            // rather than in shared code because `components {}` is built here; a
            // decoder missing on one platform is a silently blank step card on that
            // platform only, which is the hardest shape of bug to notice.
            add(SvgDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
