# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.shiyi.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.shiyi.app.**$$serializer { *; }
# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
