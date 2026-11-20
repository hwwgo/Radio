# R8 规则。开启 isMinifyEnabled 后生效。
#
# Media3 的不少组件是按类名反射创建的（DefaultMediaSourceFactory 找 HLS / DASH / RTSP
# 的 Factory，DefaultRenderersFactory 找扩展解码器）。media3-exoplayer 自带 consumer
# 规则覆盖了主要几项，这里把播放链路实际用到的再显式保一遍 —— 少一条的后果是
# 「有的电台能播、HLS 的全哑了」，而且只在 release 包里出现，很难查。
-keepclasseswithmembers class androidx.media3.exoplayer.hls.HlsMediaSource$Factory {
    <init>(androidx.media3.datasource.DataSource$Factory);
}
-keepclasseswithmembers class androidx.media3.exoplayer.dash.DashMediaSource$Factory {
    <init>(androidx.media3.datasource.DataSource$Factory);
}
-keepclasseswithmembers class androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory {
    <init>();
}

# MediaSession 会被系统媒体组件跨进程调用，类名和方法签名要留着
-keep class androidx.media3.session.MediaSession { *; }
-keep class androidx.media3.session.MediaSession$Callback { *; }
