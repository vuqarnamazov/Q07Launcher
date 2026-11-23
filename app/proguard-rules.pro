# Q07 Launcher R8 qaydaları
# android.car kitabxanası refleksiya ilə çağırılır (yalnız maşında mövcuddur)
-dontwarn android.car.**
# Stack trace-lərdə sətir nömrələri qalsın
-keepattributes SourceFile,LineNumberTable

# security-crypto (Tink) — kompilyasiya annotasiyaları işləmə zamanı lazım deyil
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.proto.** { *; }
