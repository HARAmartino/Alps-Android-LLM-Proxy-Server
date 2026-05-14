# =============================================================================
# BouncyCastle – crypto provider loaded via reflection by name at runtime.
# Must be kept in full to avoid NoSuchProviderException / ClassNotFoundException.
# =============================================================================
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
-keepnames class org.bouncycastle.** { *; }

# =============================================================================
# Ktor – server + client
#
# Ktor uses kotlinx.serialization reflective lookup for plugin registration and
# ServiceLoader for engine discovery.  The CIO engine in particular instantiates
# its pipeline components by name; stripping them causes silent 500 errors at
# runtime.  Keep all public API surfaces and internal coroutine/channel classes.
# =============================================================================
-keep class io.ktor.** { *; }
-keepnames class io.ktor.** { *; }
-dontwarn io.ktor.**

# CIO engine internals – ByteReadChannel / ByteWriteChannel wrappers used in
# the zero-copy proxy path (ProxyRouting.kt).  Removing these causes
# ChannelClosedException at runtime under R8 full mode.
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.client.engine.cio.** { *; }
-keep class io.ktor.utils.io.** { *; }
-keep class io.ktor.network.** { *; }

# ServiceLoader entries referenced by Ktor plugin discovery.
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# =============================================================================
# Kotlin coroutines
#
# R8 may inline/remove suspension machinery; keep the internal Continuation and
# DebugMetadata classes that the coroutines runtime accesses reflectively.
# =============================================================================
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# =============================================================================
# ACME4J – Let's Encrypt client uses reflection for HTTP challenge handlers.
# =============================================================================
-keep class org.shredzone.acme4j.** { *; }
-dontwarn org.shredzone.acme4j.**
-dontwarn com.google.errorprone.**

# =============================================================================
# Jetpack Compose – keep stable annotation and UI classes used by the compiler
# plugin; stripping them breaks recomposition at runtime.
# =============================================================================
-keep class androidx.compose.runtime.** { *; }
-keep @androidx.compose.runtime.Stable class * { *; }
-keep @androidx.compose.runtime.Immutable class * { *; }
-dontwarn androidx.compose.**

# =============================================================================
# WorkManager – used by the foreground service lifecycle.
# =============================================================================
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# =============================================================================
# Remove debug/verbose logging in release builds.
# Log calls are stripped by R8 when the condition is a compile-time constant,
# but android.util.Log is not – explicitly suppress verbose/debug levels here.
# =============================================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static boolean isLoggable(java.lang.String, int);
}

# =============================================================================
# Miscellaneous
# =============================================================================
-dontwarn javax.naming.**
-dontwarn sun.security.**
-dontwarn java.lang.instrument.**
-dontwarn org.slf4j.**
-dontwarn org.jose4j.**
