package org.opengoofy.index12306.ai.agentservice.chat.execution.exception;

/**
 * 表示当前实例已经失去 Turn 或 Task 的数据库执行权。
 */
public class ExecutionLeaseLostException extends RuntimeException {

    /**
     * 创建不携带业务正文的执行权丢失异常。
     *
     * @param message 服务端定位说明
     */
    public ExecutionLeaseLostException(String message) {
        super(message);
    }

    /**
     * 创建保留内部续租失败原因的执行权丢失异常。
     *
     * @param message 服务端定位说明
     * @param cause 内部数据库或事务异常
     */
    public ExecutionLeaseLostException(String message, Throwable cause) {
        super(message, cause);
    }
}
