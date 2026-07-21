package org.opengoofy.index12306.ai.agentservice.chat.exception;

import org.opengoofy.index12306.ai.agentservice.chat.controller.AgentChatController;


import jakarta.servlet.http.HttpServletRequest;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ErrorResponse;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.exception.ModelRoutingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将对话入口异常转换为不暴露下游敏感正文的稳定 HTTP 响应。
 */
@RestControllerAdvice(assignableTypes = AgentChatController.class)
public class AgentChatExceptionHandler {

    /**
     * 返回对话层已经分类的安全异常。
     *
     * @param exception 对话边界异常
     * @param request 当前 HTTP 请求
     * @return 稳定错误响应
     */
    @ExceptionHandler(AgentChatException.class)
    public ResponseEntity<ErrorResponse> handleChatException(
            AgentChatException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(
                requestId(request), exception.failureCategory(), exception.getMessage()));
    }

    /**
     * 返回多模型降级链的最终安全失败分类。
     *
     * @param exception 模型路由异常
     * @param request 当前 HTTP 请求
     * @return 服务不可用响应
     */
    @ExceptionHandler(ModelRoutingException.class)
    public ResponseEntity<ErrorResponse> handleModelException(
            ModelRoutingException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                requestId(request), exception.failureCategory().name(), "模型服务暂时不可用，请稍后重试"));
    }

    /**
     * 把缺失认证头和会话边界校验失败转换为无效请求。
     *
     * @param exception 参数异常
     * @param request 当前 HTTP 请求
     * @return 参数错误响应
     */
    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            Exception exception,
            HttpServletRequest request) {
        // 不回传仓储异常正文，避免通过会话标识探测其他用户的数据边界。
        return ResponseEntity.badRequest().body(new ErrorResponse(
                requestId(request), "INVALID_REQUEST", "请求参数无效或无权访问该会话"));
    }

    /**
     * 为未分类异常返回通用错误，避免泄露模型或工具响应内容。
     *
     * @param exception 未分类异常
     * @param request 当前 HTTP 请求
     * @return 通用服务错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                requestId(request), "INTERNAL_ERROR", "对话处理失败，请稍后重试"));
    }

    /**
     * 从请求头读取可选请求标识，用于关联客户端错误响应。
     *
     * @param request 当前 HTTP 请求
     * @return 请求标识或空值
     */
    private String requestId(HttpServletRequest request) {
        // 失败发生在命令创建前时可能没有请求标识，因此不在异常处理器中生成新值。
        return request.getHeader("X-Request-Id");
    }
}
