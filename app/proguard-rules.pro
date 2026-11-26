# ProGuard rules for NewsFilter
-keepattributes *Annotation*
-dontwarn javax.annotation.**
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}

