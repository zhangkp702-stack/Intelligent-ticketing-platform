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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.opengoofy.index12306.framework.starter.reliablecommand.annotation.ReliableCommandExecution;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandClaim;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandFingerprint;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandMode;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.exception.ReliableCommandDuplicateException;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 为 {@link ReliableCommandExecution} 建立稳定命令、重放成功结果并收敛未知结果的切面。
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public final class ReliableCommandExecutionAspect {

    private static final String FINGERPRINT_VERSION = "aop-v1";
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 512;

    private final ReliableCommandService reliableCommandService;
    private final ReliableCommandFingerprint reliableCommandFingerprint;
    private final ObjectMapper objectMapper;
    private final SpelExpressionParser expressionParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 创建可靠命令执行入口切面。
     *
     * @param reliableCommandService 远程副作用命令状态服务
     * @param reliableCommandFingerprint 稳定请求摘要器
     * @param objectMapper 成功结果序列化和重放器
     */
    public ReliableCommandExecutionAspect(
            ReliableCommandService reliableCommandService,
            ReliableCommandFingerprint reliableCommandFingerprint,
            ObjectMapper objectMapper) {
        this.reliableCommandService = Objects.requireNonNull(reliableCommandService, "reliableCommandService");
        this.reliableCommandFingerprint = Objects.requireNonNull(reliableCommandFingerprint, "reliableCommandFingerprint");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 认领远程写命令、执行一次真实调用，并为相同命令重放成功结果。
     *
     * @param joinPoint Spring AOP 目标调用
     * @return 首次调用结果或已持久化的成功结果
     * @throws Throwable 真实调用异常、重复请求冲突或结果未知异常
     */
    @Around("@annotation(org.opengoofy.index12306.framework.starter.reliablecommand.annotation.ReliableCommandExecution)")
    public Object execute(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = resolveMethod(joinPoint);
        ReliableCommandExecution annotation = Objects.requireNonNull(
                method.getAnnotation(ReliableCommandExecution.class), "reliable command annotation");
        validateSynchronousMethod(method);

        // 所有身份字段在真实调用前一次性求值，防止执行结果反向影响命令键。
        StandardEvaluationContext context = createEvaluationContext(method, joinPoint.getArgs());
        ReliableCommandDefinition definition = buildDefinition(annotation, method, context, joinPoint.getArgs());
        ReliableCommandClaim claim = reliableCommandService.claim(definition);
        if (!claim.acquired()) {
            // 重复请求绝不再次进入真实写方法，统一根据持久化状态返回、拒绝或提示对账中。
            return resolveDuplicate(method, claim);
        }

        ReliableCommandRecord record = claim.record();
        boolean succeeded = false;
        try {
            Object result = joinPoint.proceed();
            String resultPayload = writeResult(result);
            if (!reliableCommandService.markSucceeded(record, resultPayload, definition.businessReference())) {
                throw ReliableCommandDuplicateException.resultUncertain(record.key(), "命令成功结果未能通过租约确认");
            }
            succeeded = true;
            return result;
        } catch (Throwable throwable) {
            if (!succeeded) {
                // 该适配器不了解下游事实；任何异常都只允许进入 UNKNOWN，禁止据此自动重试写操作。
                reliableCommandService.markUnknown(
                        record,
                        "AOP_EXECUTION_EXCEPTION",
                        summarizeFailure(throwable),
                        Instant.now());
            }
            throw throwable;
        } finally {
            // 无论调用结束方式如何都停止 JVM 心跳，后续恢复完全由数据库状态决定。
            reliableCommandService.release(record);
        }
    }

    /**
     * 根据已持久化命令状态处理重复调用。
     *
     * @param method 被增强的方法
     * @param claim 当前命令认领结果
     * @return 已成功命令的反序列化结果
     */
    private Object resolveDuplicate(Method method, ReliableCommandClaim claim) {
        ReliableCommandRecord record = claim.record();
        return switch (claim.outcome()) {
            case REPLAY_SUCCEEDED -> readResult(method, record);
            case PROCESSING -> throw ReliableCommandDuplicateException.processing(record.key());
            case PAYLOAD_MISMATCH -> throw ReliableCommandDuplicateException.payloadMismatch(record.key());
            case OWNER_MISMATCH -> throw ReliableCommandDuplicateException.ownerMismatch(record.key());
            case TERMINAL_FAILURE -> throw ReliableCommandDuplicateException.terminalFailure(record.key(), record.failureMessage());
            case RESULT_UNCERTAIN -> throw ReliableCommandDuplicateException.resultUncertain(record.key(), record.failureMessage());
            case ACQUIRED -> throw new IllegalStateException("Acquired command cannot be resolved as duplicate");
        };
    }

    /**
     * 构造与可靠命令表字段一一对应的命令定义。
     *
     * @param annotation 入口注解配置
     * @param method 被增强的方法
     * @param context 当前参数表达式上下文
     * @param arguments 原始方法参数
     * @return 已完成字段校验的可靠命令定义
     */
    private ReliableCommandDefinition buildDefinition(
            ReliableCommandExecution annotation,
            Method method,
            StandardEvaluationContext context,
            Object[] arguments) {
        String commandId = evaluateRequired(annotation.commandId(), context, "commandId");
        String routingKey = evaluateRequired(annotation.routingKey(), context, "routingKey");
        String ownerId = evaluateRequired(annotation.ownerId(), context, "ownerId");
        Object fingerprintPayload = annotation.fingerprint().isBlank()
                ? arguments : evaluate(annotation.fingerprint(), context, "fingerprint");
        String commandType = annotation.commandType().isBlank()
                ? method.getDeclaringClass().getSimpleName() + "#" + method.getName()
                : annotation.commandType().trim();
        String businessReference = annotation.businessReference().isBlank()
                ? null : evaluateRequired(annotation.businessReference(), context, "businessReference");

        // AOP 只负责不可纳入本地事务的同步远程副作用，其他模式必须显式调用对应组件。
        return new ReliableCommandDefinition(
                new ReliableCommandKey(annotation.namespace(), commandId, routingKey),
                commandType,
                ReliableCommandMode.REMOTE_EFFECT,
                ownerId,
                reliableCommandFingerprint.calculate(fingerprintPayload),
                FINGERPRINT_VERSION,
                businessReference);
    }

    /**
     * 构造可由 SpEL 访问的当前方法参数上下文。
     *
     * @param method 被增强的方法
     * @param arguments 方法实参
     * @return 包含位置参数和可发现参数名的表达式上下文
     */
    private StandardEvaluationContext createEvaluationContext(Method method, Object[] arguments) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("args", arguments);
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        for (int index = 0; index < arguments.length; index++) {
            // 同时注册 p0、a0 与真实参数名，避免编译参数名策略影响已有调用方。
            context.setVariable("p" + index, arguments[index]);
            context.setVariable("a" + index, arguments[index]);
            if (parameterNames != null && index < parameterNames.length) {
                context.setVariable(parameterNames[index], arguments[index]);
            }
        }
        return context;
    }

    /**
     * 对 SpEL 表达式求值并要求结果为非空文本。
     *
     * @param expressionText SpEL 表达式
     * @param context 当前表达式上下文
     * @param fieldName 失败时展示的配置字段名
     * @return 规范化后的文本值
     */
    private String evaluateRequired(String expressionText, StandardEvaluationContext context, String fieldName) {
        Object value = evaluate(expressionText, context, fieldName);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(fieldName + " expression resolved to blank text");
        }
        return value.toString().trim();
    }

    /**
     * 计算单个 SpEL 表达式的值。
     *
     * @param expressionText SpEL 表达式
     * @param context 当前表达式上下文
     * @param fieldName 失败时展示的配置字段名
     * @return 原始表达式值
     */
    private Object evaluate(String expressionText, StandardEvaluationContext context, String fieldName) {
        try {
            Expression expression = expressionParser.parseExpression(expressionText);
            return expression.getValue(context);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(fieldName + " expression is invalid: " + expressionText, exception);
        }
    }

    /**
     * 将成功结果序列化为可供重复请求重放的 JSON。
     *
     * @param result 首次调用返回值
     * @return 持久化结果 JSON
     */
    private String writeResult(Object result) {
        try {
            // 即使返回值为 null 也保存 JSON null，重复调用仍可得到与首次一致的结果。
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Reliable command result cannot be serialized", exception);
        }
    }

    /**
     * 按被增强方法的泛型返回类型反序列化成功结果。
     *
     * @param method 被增强的方法
     * @param record 已成功命令记录
     * @return 与首次调用类型一致的重放结果
     */
    private Object readResult(Method method, ReliableCommandRecord record) {
        try {
            JavaType returnType = objectMapper.getTypeFactory().constructType(method.getGenericReturnType());
            return objectMapper.readValue(Objects.requireNonNull(record.resultPayload(), "resultPayload"), returnType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Reliable command result cannot be replayed", exception);
        }
    }

    /**
     * 解析代理目标上的具体方法，以读取实现类注解和精确泛型返回类型。
     *
     * @param joinPoint Spring AOP 目标调用
     * @return 目标类上的具体方法
     */
    private Method resolveMethod(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return AopUtils.getMostSpecificMethod(signature.getMethod(), joinPoint.getTarget().getClass());
    }

    /**
     * 拒绝会在方法返回后才执行副作用的异步和响应式方法。
     *
     * @param method 被增强的方法
     */
    private void validateSynchronousMethod(Method method) {
        Class<?> returnType = method.getReturnType();
        if (CompletionStage.class.isAssignableFrom(returnType)
                || "org.reactivestreams.Publisher".equals(returnType.getName())
                || isReactivePublisher(returnType)) {
            throw new IllegalStateException(
                    "@ReliableCommandExecution does not support asynchronous or reactive return types");
        }
    }

    /**
     * 检查返回类型是否实现响应式流接口，同时避免框架模块直接依赖 Reactor。
     *
     * @param returnType 方法返回类型
     * @return 返回类型为响应式发布者时返回 true
     */
    private boolean isReactivePublisher(Class<?> returnType) {
        for (Class<?> interfaceType : returnType.getInterfaces()) {
            if ("org.reactivestreams.Publisher".equals(interfaceType.getName()) || isReactivePublisher(interfaceType)) {
                return true;
            }
        }
        Class<?> superclass = returnType.getSuperclass();
        return superclass != null && superclass != Object.class && isReactivePublisher(superclass);
    }

    /**
     * 生成可持久化的限长异常摘要，避免将敏感堆栈写入命令表。
     *
     * @param throwable 原始异常
     * @return 异常类型和消息组成的限长摘要
     */
    private String summarizeFailure(Throwable throwable) {
        String message = throwable.getMessage();
        String summary = throwable.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return summary.length() <= MAX_FAILURE_MESSAGE_LENGTH
                ? summary : summary.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }
}
