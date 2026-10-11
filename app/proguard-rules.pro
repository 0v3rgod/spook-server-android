# O :core e reflection-free, entao nao ha quase nada a manter. O que importa:
# o socket.io do outro lado e JSON puro, e nenhuma classe e procurada por nome.

# kotlinx.serialization gera serializers sinteticos
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.taylor.spook.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.taylor.spook.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor usa ServiceLoader para achar o engine
-keep class io.ktor.server.engine.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
