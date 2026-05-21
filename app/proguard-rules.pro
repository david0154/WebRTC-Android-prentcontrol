# ============================================================
# WebRTC Android ParentControl — ProGuard / R8 keep rules
# Required for release APK: isMinifyEnabled = true
# Without these, R8 will strip reflective SDK code and crash.
# ============================================================

# --- WebRTC (stream-webrtc-android) -------------------------
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# --- Socket.IO client ----------------------------------------
-keep class io.socket.** { *; }
-keepclassmembers class io.socket.** { *; }
-dontwarn io.socket.**

# --- Agora RTM -----------------------------------------------
-keep class io.agora.** { *; }
-keepclassmembers class io.agora.** { *; }
-dontwarn io.agora.**

# --- Firebase ------------------------------------------------
-keep class com.google.firebase.** { *; }
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# --- Google Play Services (Location, GMS) --------------------
-keep class com.google.android.gms.** { *; }
-keepclassmembers class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# --- WorkManager ---------------------------------------------
-keep class androidx.work.** { *; }
-keepclassmembers class androidx.work.** { *; }

# --- OkHttp (Socket.IO dependency) ---------------------------
-keep class okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# --- JSON ----------------------------------------------------
-keep class org.json.** { *; }

# --- Our own service/receiver classes (never obfuscate) ------
-keep class com.example.wallpaperapplication.StreamingService { *; }
-keep class com.example.wallpaperapplication.CaptureManager { *; }
-keep class com.example.wallpaperapplication.BootReceiver { *; }
-keep class com.example.wallpaperapplication.DataSyncWorker { *; }
-keep class com.example.wallpaperapplication.MainActivity { *; }
-keep class com.example.wallpaperapplication.ConsentActivity { *; }
-keep class com.example.wallpaperapplication.SettingsRepository { *; }
-keep class com.example.wallpaperapplication.StreamingSettingsActivity { *; }

# --- Enum safety ---------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Parcelable ----------------------------------------------
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# --- Serializable --------------------------------------------
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- Keep line numbers for crash reports ---------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
