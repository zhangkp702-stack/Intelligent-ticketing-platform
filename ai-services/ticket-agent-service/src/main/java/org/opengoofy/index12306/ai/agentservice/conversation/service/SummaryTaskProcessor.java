package org.opengoofy.index12306.ai.agentservice.conversation.service;

/**
 * 阶段四实现的摘要模型处理端口，阶段三只定义稳定输入输出契约。
 */
public interface SummaryTaskProcessor {

    /**
     * 根据旧摘要和新增原始消息生成下一完整摘要版本。
     *
     * @param workItem 已领取的摘要任务输入
     * @return 新摘要内容和实际模型信息
     * @throws Exception 模型调用或结构化结果校验失败
     */
    SummaryTaskService.SummaryGenerationResult process(SummaryTaskService.SummaryWorkItem workItem) throws Exception;
}
