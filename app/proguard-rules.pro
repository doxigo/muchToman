# kotlinx.serialization keeps its generated serializers via companion objects.
-keepclassmembers class com.doxigo.muchtoman.** {
    *** Companion;
}
-keepclasseswithmembers class com.doxigo.muchtoman.** {
    kotlinx.serialization.KSerializer serializer(...);
}
