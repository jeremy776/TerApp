# =============================================================================
# TerApp - ProGuard / R8 Rules
# =============================================================================

# Preserve all native JNI method bindings
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve JNI Bridge & Data models
-keep class com.terapp.TerAppBridge { *; }
-keep class com.terapp.ThemeManager { *; }
-keep class com.terapp.TerminalTheme { *; }
-keep class com.terapp.ColorSpan { *; }

# Keep Material & AndroidX components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
