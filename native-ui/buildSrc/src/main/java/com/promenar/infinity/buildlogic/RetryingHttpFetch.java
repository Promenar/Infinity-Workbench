package com.promenar.infinity.buildlogic;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/** 对构建输入下载中的暂时性连接或服务错误进行有界重试。 */
final class RetryingHttpFetch {
    private RetryingHttpFetch() {}

    static HttpResponse<InputStream> send(HttpClient client, HttpRequest request, int attempts, Duration delay,
            Consumer<String> warn)
            throws IOException, InterruptedException {
        if (attempts < 1 || delay.isNegative()) throw new IllegalArgumentException("重试参数无效");
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (attempt == attempts || !(status == 408 || status == 429 || status >= 500 && status <= 599)) {
                    return response;
                }
                response.body().close();
                warn.accept("构建输入下载返回 HTTP " + status + "，准备重试（" + attempt + "/" + attempts + "）。");
            } catch (IOException failure) {
                if (attempt == attempts) throw failure;
                warn.accept("构建输入下载连接失败，准备重试（" + attempt + "/" + attempts + "）：" + failure.getClass().getSimpleName());
            }
            Thread.sleep(delay.multipliedBy(attempt).toMillis());
        }
    }
}
