# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 保留行号信息，方便崩溃分析
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ========== Kotlin 协程 / 反射 / 序列化 ==========
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ========== Room 数据库 ==========
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * { @androidx.room.Entity <fields>; }
-keep class * { @androidx.room.Dao <methods>; }
-keep class * { @androidx.room.TypeConverter <methods>; }
-keep class com.example.stockdiary.database.** { *; }
-keep class androidx.sqlite.db.** { *; }
-dontwarn androidx.room.paging.**

# ========== Gson 序列化/反序列化 ==========
-keep class com.google.gson.** { *; }
-keep class com.example.stockdiary.data.ExportData { *; }
-keep class com.example.stockdiary.data.ImportResult { *; }
-keep class com.example.stockdiary.data.ExportResult { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ========== Compose / AndroidX ==========
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }

# ========== ViewModel Factory ==========
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}
-keep class * extends androidx.lifecycle.ViewModelProvider$Factory { *; }

# ========== WebView ==========
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ========== OkHttp / Retrofit ==========
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.squareup.okhttp3.** { *; }
