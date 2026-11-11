-dontwarn **

# TDLib (td-android): libtdjson_jni.so registers natives on org.drinkless.tdlib.JsonClient and
# looks classes up by name, so nothing in this package may be renamed, removed or inlined.
-keep class org.drinkless.tdlib.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
