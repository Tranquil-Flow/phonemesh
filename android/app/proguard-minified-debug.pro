# The androidTest APK relies on the app for the Kotlin stdlib that androidx.test uses;
# keep it whole in the instrumented build only (release shrinks it).
-keep class kotlin.** { *; }
