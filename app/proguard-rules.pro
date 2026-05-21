# FIX Bug 21 — ProGuard keep rules for all reflection-based SDKs

# WebRTC (stream.webrtc.android)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Socket.IO client
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# OkHttp / Okio
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Agora RTM
-keep class io.agora.** { *; }
-dontwarn io.agora.**

# WorkManager
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# App classes used via reflection
-keep class com.example.wallpaperapplication.** { *; }

# Preserve source file / line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
