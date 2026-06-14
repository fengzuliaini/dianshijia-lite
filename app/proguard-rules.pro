# 仅执行无用代码裁剪（Shrink），不进行重命名混淆与字节码优化，保留完整的调试断点
-dontobfuscate
-dontoptimize
-keepattributes SourceFile,LineNumberTable

# 保留 IjkPlayer 播放器核心，防止裁剪掉 JNI 回调和原生反射方法
-keep class tv.danmaku.ijk.media.player.** { *; }

# 保留应用内的反射、网络解析和数据实体类，防止反序列化失效
-keep class com.dianshijia.lite.model.** { *; }
-keep class com.dianshijia.lite.parser.** { *; }
-keep class com.dianshijia.lite.MainActivity$UpdateInfo { *; }

# 忽略 OkHttp 和 Okio 相关的警告以顺利完成编译，并防止底层网络库被误删
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
