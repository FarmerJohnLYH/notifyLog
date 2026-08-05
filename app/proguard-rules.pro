# Manifest-declared components (Service / Receiver / Activity) are kept by AGP
# automatically, so no explicit keep rules are needed for them.

# Room generates its own keep rules; nothing extra required for entities/DAOs.

# Keep Kotlin metadata for reflection-free coroutines (default R8 handles this).
-dontwarn kotlinx.coroutines.**
