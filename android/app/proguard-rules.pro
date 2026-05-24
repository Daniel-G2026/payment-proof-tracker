# R8 / ProGuard rules for ProofLedger release builds.
#
# Most libraries we use (Hilt, Room, Retrofit, OkHttp, Coroutines, AndroidX)
# ship their own consumer rules inside their AARs, so we only need to cover:
#
#   1. Stack-trace preservation so Play Console / Crashlytics can map crashes
#      back to source even after method renaming.
#   2. Moshi DTOs — adapters are generated at compile time, but the DTO
#      classes themselves still need to keep their constructors and field
#      names intact for the adapter to find them.
#   3. kotlinx.serialization — Supabase uses this to round-trip UserSession.
#      Its generated $serializer companions are reachable only by reflection.
#   4. Our Room TypeConverter enums — SyncStatus etc. are stored as their
#      enum name; valueOf() at read time would fail if R8 renamed them.

# ---- 1. Stack-trace preservation ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations so reflection-driven libraries (Moshi @Json, Room @Entity,
# kotlinx.serialization @Serializable, Retrofit @GET/@POST) keep working.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ---- 2. Moshi ----
# Generated adapters live in *_JsonAdapter classes. Keep them so Moshi's
# runtime adapter lookup can find them by name.
-keep class **JsonAdapter { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
# Keep our own DTO package as a belt-and-suspenders so even if a future class
# forgets @JsonClass, it still parses.
-keep class com.danielanazodo.proofledger.data.remote.dto.** { *; }

# ---- 3. kotlinx.serialization (used by Supabase) ----
# Keep generated serializers and Companion objects.
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep every class annotated @Serializable along with its fields.
-keep @kotlinx.serialization.Serializable class * { *; }
# Supabase's auth-kt models use this annotation transitively.
-keep class io.github.jan.supabase.** { *; }
-keepclassmembers class io.github.jan.supabase.** { *; }
# Ktor (Supabase's HTTP transport).
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }

# ---- 4. Room TypeConverter enums ----
# Persisted by name via Enum.valueOf(), so the enum names must survive R8.
-keepclassmembers enum com.danielanazodo.proofledger.data.local.SyncStatus { *; }
-keepclassmembers enum com.danielanazodo.proofledger.data.local.ProofBackupStatus { *; }
-keepclassmembers enum com.danielanazodo.proofledger.data.local.ProofBackupPreference { *; }

# ---- General Kotlin / coroutines safety net ----
# Most of this is covered by consumer rules, but a few corners are flaky.
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ---- Missing transitive classes that aren't on Android ----
# Tink (Supabase's crypto) references error_prone annotations at compile time
# but doesn't need them at runtime; they're not in the AGP classpath.
-dontwarn com.google.errorprone.annotations.**
# Ktor's IntellijIdeaDebugDetector probes java.lang.management, which exists
# on the JVM but not on Android. The probe fails closed (no debugger detected)
# which is exactly what we want in a release APK anyway.
-dontwarn java.lang.management.**
