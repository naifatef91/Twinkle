# --- kotlinx.serialization (wire messages in app.twinkle.engine, web, data) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.twinkle.**$$serializer { *; }
-keepclassmembers class app.twinkle.** { *** Companion; }
-keepclasseswithmembers class app.twinkle.** { kotlinx.serialization.KSerializer serializer(...); }


# --- ZXing embedded scanner reflects on its capture activity ---
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# --- Keep stack traces readable in Play Console ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
