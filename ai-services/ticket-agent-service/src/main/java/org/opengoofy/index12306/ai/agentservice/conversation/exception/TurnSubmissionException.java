package org.opengoofy.index12306.ai.agentservice.conversation.exception;

/**
 * 表示预创建轮次首次提交或重复提交违反不可变协议。
 */
public class TurnSubmissionException extends RuntimeException {

    private final Reason reason;

    /**
     * 创建带稳定失败原因的轮次提交异常。
     *
     * @param reason 稳定失败原因
     * @param message 仅供服务端定位的异常说明
     */
    public TurnSubmissionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * 返回可映射为安全接口错误的稳定原因。
     *
     * @return 提交失败原因
     */
    public Reason reason() {
        return reason;
    }

    /**
     * 轮次提交协议允许对外暴露的稳定失败分类。
     */
    public enum Reason {
        INVALID_TOKEN,
        SUBMISSION_EXPIRED,
        PAYLOAD_MISMATCH
    }
}
