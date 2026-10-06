# Readable stack traces; the size win comes from shrinking libraries, not renaming.
-dontobfuscate
# App code is small and exercised by instrumentation; keep it whole.
-keep class org.phonemesh.** { *; }
# sherpa-onnx: classes and members are reached from JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# protobuf-lite reads generated message fields reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# Annotation-only classes referenced by androidx.test (test APK); not needed at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
