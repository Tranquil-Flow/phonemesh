# Readable stack traces; the size win comes from shrinking libraries, not renaming.
-dontobfuscate
# Shrink and optimize app/core without renaming; preserve runtime boundaries below.
# Android manifest entrypoints are retained by the Android plugin's generated rules.
-keep class !org.phonemesh.core.**,!org.phonemesh.app.**,org.phonemesh.** { *; }
-keep,allowshrinking,allowoptimization class org.phonemesh.core.** { *; }
# Keep network callsites fixed for the dex network-boundary gate.
-keep class org.phonemesh.core.hub.HubClient { *; }
-keep class org.phonemesh.core.hub.HubEngineTls { *; }
-keep,allowshrinking,allowoptimization class org.phonemesh.app.** { *; }
-keep class org.phonemesh.app.PackDownloads { *; }
-keep class org.phonemesh.app.WifiHubLink { *; }
# Explicit JNI and diagnostic reflection boundaries.
-keep class org.phonemesh.app.NativeUpscale { *; }
-keep class org.phonemesh.app.NativeGpu { *; }
-keep class org.phonemesh.app.*Probe { *; }
# sherpa-onnx: classes and members are reached from JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# protobuf-lite reads generated message fields reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# Annotation-only classes referenced by androidx.test (test APK); not needed at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
