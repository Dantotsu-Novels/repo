# Keep NovelInterface implementations and models
-keep public class * implements ani.dantotsu.parsers.NovelInterface {
    public <init>();
    *;
}

-keep class ani.dantotsu.parsers.** { *; }
-keep class com.lagradost.nicehttp.** { *; }

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Jsoup rules
-keep public class org.jsoup.** { public *; }
