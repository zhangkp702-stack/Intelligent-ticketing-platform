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
        /** 首次提交令牌缺失、伪造或与当前轮次不匹配。 */
        INVALID_TOKEN,
        /** 草稿轮次的首次提交时限已过。 */
        SUBMISSION_EXPIRED,
        /** 同一轮次重试时携带了不同的问题内容。 */
        PAYLOAD_MISMATCH
    }
}
