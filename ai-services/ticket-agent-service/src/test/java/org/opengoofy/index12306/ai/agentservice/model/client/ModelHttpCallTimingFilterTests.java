package org.opengoofy.index12306.ai.agentservice.model.client;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证模型 HTTP 计时过滤器不会改变流式响应体的单次消费语义。
 */
class ModelHttpCallTimingFilterTests {

    /**
     * 验证过滤器只订阅一次响应体，并将原始数据完整传递给下游。
     */
    @Test
    void responseBodyIsConsumedOnlyOnce() {
        AtomicInteger subscriptionCount = new AtomicInteger();

        // 构造只允许订阅一次的响应流，第二次订阅立即复现线上异常。
        Flux<DataBuffer> singleUseBody = Flux.defer(() -> {
            if (subscriptionCount.incrementAndGet() > 1) {
                return Flux.error(new IllegalStateException("The client response body can only be consumed once"));
            }
            byte[] payload = "data: {\"content\":\"ok\"}\n\n".getBytes(StandardCharsets.UTF_8);
            return Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(payload));
        });
        ExchangeFunction exchangeFunction = ignored -> Mono.just(
                ClientResponse.create(HttpStatus.OK).body(singleUseBody).build());
        ClientRequest request = ClientRequest.create(
                HttpMethod.POST, URI.create("http://localhost/v1/chat/completions")).build();
        ExchangeFilterFunction filter = ModelHttpCallTimingFilter.create(
                "provider-a", "candidate-a", "model-a");

        // 消费过滤后的响应，确认计时监听没有提前读取或重复订阅原始响应体。
        Flux<String> responseBody = filter.filter(request, exchangeFunction)
                .flatMapMany(response -> response.bodyToFlux(DataBuffer.class))
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return new String(bytes, StandardCharsets.UTF_8);
                });

        StepVerifier.create(responseBody)
                .expectNext("data: {\"content\":\"ok\"}\n\n")
                .verifyComplete();
        assertThat(subscriptionCount).hasValue(1);
    }
}
