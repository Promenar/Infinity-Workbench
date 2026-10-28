package com.promenar.infinity.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.local.ProviderConnectionOutcome
import com.promenar.infinity.data.local.ProviderConnectionRecord
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.infinity.ui.common.InfinityStatusKind
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** 当前时间，每分钟刷新一次，让“N 分钟前”随时间推进。 */
@Composable
internal fun rememberMinuteClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
internal fun relativeTimeLabel(thenMillis: Long, nowMillis: Long): String =
    when (val bucket = relativeTimeBucket(thenMillis, nowMillis)) {
        RelativeTimeBucket.JustNow -> stringResource(R.string.relative_time_just_now)
        is RelativeTimeBucket.Minutes -> pluralStringResource(R.plurals.relative_time_minutes, bucket.value, bucket.value)
        is RelativeTimeBucket.Hours -> pluralStringResource(R.plurals.relative_time_hours, bucket.value, bucket.value)
        is RelativeTimeBucket.Days -> pluralStringResource(R.plurals.relative_time_days, bucket.value, bucket.value)
        is RelativeTimeBucket.Date -> {
            val locale = LocalConfiguration.current.locales[0]
            DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(bucket.epochMillis))
        }
    }

/** 连接测试记录的语义色。 */
internal fun ProviderConnectionRecord.statusKind(): InfinityStatusKind = when (outcome) {
    ProviderConnectionOutcome.SUCCESS -> InfinityStatusKind.Success
    ProviderConnectionOutcome.UNSUPPORTED -> InfinityStatusKind.Info
    ProviderConnectionOutcome.FAILED -> InfinityStatusKind.Error
}

/** 连接测试结果的短文案（列表行用），如“连接正常”“密钥无效”。 */
@Composable
internal fun ProviderConnectionRecord.outcomeLabel(): String = when (outcome) {
    ProviderConnectionOutcome.SUCCESS -> stringResource(R.string.provider_connection_ok)
    ProviderConnectionOutcome.UNSUPPORTED -> stringResource(R.string.provider_connection_untestable)
    ProviderConnectionOutcome.FAILED -> when (failure) {
        ProviderConnectionProbeFailure.CREDENTIALS_MISSING -> stringResource(R.string.provider_connection_failed_key_missing)
        ProviderConnectionProbeFailure.CREDENTIALS_INVALID,
        ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED,
        -> stringResource(R.string.provider_connection_failed_auth)
        ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE -> stringResource(R.string.provider_connection_failed_network)
        ProviderConnectionProbeFailure.ENDPOINT_INVALID -> stringResource(R.string.provider_connection_failed_endpoint)
        ProviderConnectionProbeFailure.RESPONSE_INVALID, null -> stringResource(R.string.provider_connection_failed)
    }
}
