package org.opengoofy.index12306.ai.agentservice.infra.model.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将模型输出限制并解析为约定的 JSON 对象。
 */
@Component
public class ModelJsonOutputParser {

    private static final int MAX_OUTPUT_LENGTH = 65_536;

    private final ObjectMapper objectMapper;

    /**
     * 创建模型 JSON 输出解析器。
     *
     * @param objectMapper 应用统一的 JSON 映射器
     */
    public ModelJsonOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从模型输出中提取单个 JSON 对象并转换为目标类型。
     *
     * @param content 模型输出文本
     * @param resultType 目标结构类型
     * @param <T> 目标结构类型
     * @return 解析后的结构化结果
     */
    public <T> T parse(String content, Class<T> resultType) {
        // 在解析前限制输出规模，避免异常响应占用过多内存。
        if (!StringUtils.hasText(content) || content.length() > MAX_OUTPUT_LENGTH) {
            throw new InvalidModelOutputException("模型结构化输出为空或超过长度限制");
        }
        int objectStart = content.indexOf('{');
        int objectEnd = content.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new InvalidModelOutputException("模型结构化输出不是 JSON 对象");
        }

        // 兼容模型附加 Markdown 围栏的情况，但只接受首尾之间的一个 JSON 对象。
        String json = content.substring(objectStart, objectEnd + 1);
        try {
            return objectMapper.readValue(json, resultType);
        } catch (JsonProcessingException ex) {
            throw new InvalidModelOutputException("模型结构化输出解析失败", ex);
        }
    }
}
