# Keep native JNI entrypoints for hev-socks5-tunnel
-keep class org.amnezia.awg.hevtunnel.** { *; }
-keepclassmembers class org.amnezia.awg.hevtunnel.** {
    native <methods>;
}

# Keep Room entities & converters
-keep class com.carlese.simpleproxy.app.data.ProxyProfile { *; }
-keep class com.carlese.simpleproxy.app.data.ProxyProtocol { *; }
-keep class com.carlese.simpleproxy.app.data.ProxyConverters { *; }

# Keep Crashlytics stacktrace line numbers
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
