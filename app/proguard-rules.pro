# kotlinx.serialization 反射与序列化器保留
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.hecate2.D7.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.github.hecate2.D7.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}