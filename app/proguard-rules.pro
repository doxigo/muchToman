# Room builds its generated WorkDatabase_Impl by reflection, through the no-argument
# constructor. Room 2.6.1 — which is what WorkManager 2.10.1 drags in — ships
# "-keep class * extends androidx.room.RoomDatabase" with no member spec, and under R8's
# full mode that keeps the class while shrinking the constructor away. The release build
# then died on process start, before a single frame:
#     Unable to get provider androidx.startup.InitializationProvider:
#     NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# Every install of 1.0.2, on every device. Room 2.7 ships this same member spec itself, so
# this line becomes redundant the day WorkManager pulls in a newer Room — harmless either way.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# kotlinx.serialization keeps its generated serializers via companion objects.
-keepclassmembers class com.doxigo.muchtoman.** {
    *** Companion;
}
-keepclasseswithmembers class com.doxigo.muchtoman.** {
    kotlinx.serialization.KSerializer serializer(...);
}
