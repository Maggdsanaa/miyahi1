# Rules are only needed if you enable isMinifyEnabled in release.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-keep class org.videolan.** { *; }
