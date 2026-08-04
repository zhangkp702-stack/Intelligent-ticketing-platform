/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.framework.starter.reliablecommand.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.framework.starter.reliablecommand.annotation.ReliableCommandExecution;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandClaim;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandFingerprint;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandMode;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.exception.ReliableCommandDuplicateException;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证可靠命令 AOP 只在同步远程执行入口认领命令并安全处理重复请求。
 */
public class ReliableCommandExecutionAspectTests {

    /**
     * 验证首次认领后只执行一次目标方法，并持久化可供重放的 JSON 结果。
     */
    @Test
    void shouldExecuteAcquiredCommandAndPersistResult() {
        ReliableCommandService service = mock(ReliableCommandService.class);
        ReliableCommandRecord record = processingRecord();
        when(service.claim(any())).thenReturn(new ReliableCommandClaim(ReliableCommandClaim.Outcome.ACQUIRED, record));
        when(service.markSucceeded(any(), anyString(), nullable(String.class))).thenReturn(true);
        SampleCommand target = new SampleCommand();
        SampleCommand proxy = proxy(target, service);

        // 首次调用获得执行权后进入真实远程写边界。
        SampleResponse response = proxy.execute(new SampleRequest("command-1", "route-1", "user-1", "payload"));

        assertThat(response.value()).isEqualTo("executed-payload");
        assertThat(target.invocationCount()).hasValue(1);
        verify(service).markSucceeded(any(), anyString(), nullable(String.class));
        verify(service).release(record);
    }

    /**
     * 验证已成功命令直接重放结果，绝不再次调用目标方法。
     */
    @Test
    void shouldReplaySucceededResultWithoutInvokingTarget() {
        ReliableCommandService service = mock(ReliableCommandService.class);
        ReliableCommandRecord record = succeededRecord("{\"value\":\"persisted\"}");
        when(service.claim(any())).thenReturn(new ReliableCommandClaim(ReliableCommandClaim.Outcome.REPLAY_SUCCEEDED, record));
        SampleCommand target = new SampleCommand();
        SampleCommand proxy = proxy(target, service);

        // 重试请求应使用命令表结果，不重新触发远程写副作用。
        SampleResponse response = proxy.execute(new SampleRequest("command-1", "route-1", "user-1", "payload"));

        assertThat(response.value()).isEqualTo("persisted");
        assertThat(target.invocationCount()).hasValue(0);
        verify(service, never()).markSucceeded(any(), anyString(), nullable(String.class));
    }

    /**
     * 验证正在执行中的相同命令以稳定异常返回，而不允许并发重入。
     */
    @Test
    void shouldRejectProcessingDuplicateWithoutInvokingTarget() {
        ReliableCommandService service = mock(ReliableCommandService.class);
        ReliableCommandRecord record = processingRecord();
        when(service.claim(any())).thenReturn(new ReliableCommandClaim(ReliableCommandClaim.Outcome.PROCESSING, record));
        SampleCommand target = new SampleCommand();
        SampleCommand proxy = proxy(target, service);

        // 处理中的命令必须等待首个实例完成或由恢复器接管。
        assertThatThrownBy(() -> proxy.execute(new SampleRequest("command-1", "route-1", "user-1", "payload")))
                .isInstanceOf(ReliableCommandDuplicateException.class)
                .extracting(exception -> ((ReliableCommandDuplicateException) exception).reason())
                .isEqualTo(ReliableCommandDuplicateException.Reason.PROCESSING);
        assertThat(target.invocationCount()).hasValue(0);
    }

    /**
     * 验证执行异常不会删除命令，而是持久化为等待权威对账的 UNKNOWN。
     */
    @Test
    void shouldMarkUnknownWhenTargetThrows() {
        ReliableCommandService service = mock(ReliableCommandService.class);
        ReliableCommandRecord record = processingRecord();
        when(service.claim(any())).thenReturn(new ReliableCommandClaim(ReliableCommandClaim.Outcome.ACQUIRED, record));
        FailingCommand target = new FailingCommand();
        FailingCommand proxy = proxy(target, service);

        // 下游超时或连接中断不能被 AOP 误判为业务失败，只能进入后续对账。
        assertThatThrownBy(() -> proxy.execute(new SampleRequest("command-1", "route-1", "user-1", "payload")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("downstream timeout");
        verify(service).markUnknown(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(Instant.class));
        verify(service).release(record);
    }

    /**
     * 验证异步返回类型在认领前被拒绝，防止方法返回后真实副作用才开始执行。
     */
    @Test
    void shouldRejectAsynchronousReturnTypeBeforeClaimingCommand() {
        ReliableCommandService service = mock(ReliableCommandService.class);
        AsyncCommand target = new AsyncCommand();
        AsyncCommand proxy = proxy(target, service);

        // AOP 无法跟踪未来执行的 CompletionStage，因此不能把它标记为同步可靠命令。
        assertThatThrownBy(() -> proxy.execute(new SampleRequest("command-1", "route-1", "user-1", "payload")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support asynchronous");
        verify(service, never()).claim(any());
    }

    /**
     * 创建带有可靠命令切面的 CGLIB 代理。
     *
     * @param target 被增强的示例命令
     * @param service 模拟的可靠命令状态服务
     * @param <T> 命令类型
     * @return 目标代理
     */
    private <T> T proxy(T target, ReliableCommandService service) {
        ReliableCommandFingerprint fingerprint = payload -> "fingerprint-" + payload.hashCode();
        ReliableCommandExecutionAspect aspect = new ReliableCommandExecutionAspect(
                service, fingerprint, new ObjectMapper());
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    /**
     * 构造持有有效执行租约的处理中命令记录。
     *
     * @return 模拟首次认领后的命令记录
     */
    private ReliableCommandRecord processingRecord() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        return new ReliableCommandRecord(
                new ReliableCommandKey("sample-command", "command-1", "route-1"),
                "SampleCommand#execute", ReliableCommandMode.REMOTE_EFFECT, "user-1",
                "fingerprint", "aop-v1", ReliableCommandStatus.PROCESSING,
                null, null, null, null, "worker-1", now.plusSeconds(60), 1L,
                now, 1, null, 0, now, now);
    }

    /**
     * 构造已经成功且携带可重放 JSON 结果的命令记录。
     *
     * @param resultPayload 可重放结果 JSON
     * @return 成功命令记录
     */
    private ReliableCommandRecord succeededRecord(String resultPayload) {
        ReliableCommandRecord processing = processingRecord();
        return new ReliableCommandRecord(
                processing.key(), processing.commandType(), processing.mode(), processing.ownerId(),
                processing.requestFingerprint(), processing.fingerprintVersion(), ReliableCommandStatus.SUCCEEDED,
                resultPayload, null, null, null, null, null, 1L, null, 1,
                null, 0, processing.createdAt(), processing.updatedAt());
    }

    /**
     * 表示来自服务端已确认动作的最小命令参数。
     *
     * @param commandId 稳定命令标识
     * @param routingKey 数据库路由键
     * @param ownerId 命令所属用户
     * @param payload 参与请求摘要的业务参数
     */
    private record SampleRequest(String commandId, String routingKey, String ownerId, String payload) {
    }

    /**
     * 表示可被 Jackson 持久化和重放的最小执行结果。
     *
     * @param value 业务返回值
     */
    private record SampleResponse(String value) {
    }

    /**
     * 模拟同步远程写执行边界。
     */
    public static class SampleCommand {

        private final AtomicInteger invocationCount = new AtomicInteger();

        /**
         * 执行一次示例远程写操作。
         *
         * @param request 已校验的稳定命令参数
         * @return 可持久化的执行结果
         */
        @ReliableCommandExecution(
                namespace = "sample-command",
                commandId = "#p0.commandId",
                routingKey = "#p0.routingKey",
                ownerId = "#p0.ownerId",
                fingerprint = "#p0.payload")
        public SampleResponse execute(SampleRequest request) {
            // 计数器代表不可重复触发的下游写调用。
            invocationCount.incrementAndGet();
            return new SampleResponse("executed-" + request.payload());
        }

        /**
         * 返回真实写边界被调用的次数。
         *
         * @return 调用次数原子计数器
         */
        AtomicInteger invocationCount() {
            return invocationCount;
        }
    }

    /**
     * 模拟不允许接入同步可靠命令 AOP 的异步执行边界。
     */
    public static class AsyncCommand {

        /**
         * 返回未来执行结果，用于验证 AOP 在认领前拒绝异步类型。
         *
         * @param request 已校验的稳定命令参数
         * @return 尚未执行完成的未来结果
         */
        @ReliableCommandExecution(
                namespace = "sample-command",
                commandId = "#p0.commandId",
                routingKey = "#p0.routingKey",
                ownerId = "#p0.ownerId")
        public CompletableFuture<String> execute(SampleRequest request) {
            // 此方法不应被调用；真实异步任务需要自己的持久化状态机。
            return CompletableFuture.completedFuture(request.payload());
        }
    }

    /**
     * 模拟远程调用抛出异常的同步执行边界。
     */
    public static class FailingCommand {

        /**
         * 模拟远程写服务在结果返回前超时。
         *
         * @param request 已校验的稳定命令参数
         * @return 永不返回的业务结果
         */
        @ReliableCommandExecution(
                namespace = "sample-command",
                commandId = "#p0.commandId",
                routingKey = "#p0.routingKey",
                ownerId = "#p0.ownerId")
        public SampleResponse execute(SampleRequest request) {
            // 真实系统此处代表连接中断，AOP 不具备下游事实，不能擅自重试。
            throw new IllegalStateException("downstream timeout");
        }
    }
}
