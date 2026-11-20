package com.radio.simple.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * 网络电台的重试策略。
 *
 * 默认的 [DefaultLoadErrorHandlingPolicy] 对 HLS 清单和分片只重试 3 次、最长间隔 5 秒：
 * 服务器抖十来秒，播放器就报错停下。对点播无所谓，对收音机不行 ——
 * 用户不会盯着屏幕，断了就是「播着播着没声了」。
 *
 * 这里按错误类型分开处理：
 *
 * | 情况 | 处理 |
 * | --- | --- |
 * | 403（防盗链 / token 过期） | **不重试**。同一个地址再请求一百次也是 403 |
 * | 404 / 410（资源没了） | 试 2 次。HLS 分片刚刷新、还没上传完时会出现 |
 * | 网络超时、连接重置、5xx | 8 次，间隔逐步拉到 15 秒 |
 *
 * 重试全部用尽后仍然会报错，交给界面层的「自动重连」去重新准备播放器 ——
 * 那一层能拿到新的清单会话，比重试同一个分片更有用。
 */
@OptIn(UnstableApi::class)
internal class RadioLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_RETRIES

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // 先让默认策略拦掉它认为不该重试的（解析错误、文件不存在等）
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        return retryDelayMs(responseCodeOf(loadErrorInfo.exception), loadErrorInfo.errorCount)
    }

    internal companion object {

        /** Loader 允许的最大重试次数；真正的截止由 [retryDelayMs] 按错误类型决定 */
        const val MAX_RETRIES = 10

        /** 网络类错误最多等这么久再试 */
        const val MAX_DELAY_MS = 15_000L

        /**
         * 第 [errorCount] 次失败后该等多久；[C.TIME_UNSET] 表示别再试了。
         *
         * 纯函数，方便直接测：不给 responseCode（连接超时、DNS 失败等）时按网络错误处理。
         */
        fun retryDelayMs(responseCode: Int?, errorCount: Int): Long {
            val limit = when (responseCode) {
                // 403 = 防盗链 / token 过期，重试没有意义
                403 -> 0
                // 404 / 410 = 资源暂时不在（HLS 分片滞后上传），给两次机会
                404, 410 -> 2
                else -> 8
            }
            if (errorCount > limit) return C.TIME_UNSET
            // 2s、4s、6s…最多 15s。八次加起来约 70 秒，够撑过一次 CDN 断连，
            // 又不至于让「链接真的坏了」的用户等太久才看到提示
            return (errorCount * 2_000L).coerceAtMost(MAX_DELAY_MS)
        }

        /** 从异常链里捞 HTTP 状态码；没有就是 null */
        fun responseCodeOf(error: Throwable?): Int? {
            var cause = error
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
                cause = cause.cause
            }
            return null
        }
    }
}
