# Gson uses reflection for API DTOs and persisted/cache model objects.
# Keep these names stable before R8 is re-enabled for release builds.
-keepattributes Signature,*Annotation*
-keep class com.rteats.mpeineo.data.Remote*Dto { *; }
-keep class com.rteats.mpeineo.model.** { *; }
