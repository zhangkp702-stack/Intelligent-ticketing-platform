package org.opengoofy.index12306.ai.agentservice.infra.model.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证模型 JSON 输出的围栏兼容和非法结构拒绝规则。
 */
class ModelJsonOutputParserTests {

    private final ModelJsonOutputParser parser = new ModelJsonOutputParser(new ObjectMapper());

    /**
     * 验证 Markdown 围栏之外的说明会被剥离，并正确解析内部 JSON 对象。
     */
    @Test
    void parsesJsonObjectInsideMarkdownFence() {
        // 模拟部分兼容模型会在 JSON 前后附加围栏或简短说明的响应。
        ParsedOutput output = parser.parse(
                "```json\n{\"decision\":\"CREATE_NEW\"}\n```",
                ParsedOutput.class);

        assertThat(output.decision()).isEqualTo("CREATE_NEW");
    }

    /**
     * 验证不包含 JSON 对象的模型响应会转为可降级结构异常。
     */
    @Test
    void rejectsNonJsonOutput() {
        // 非结构化自然语言不得进入主题路由或摘要业务逻辑。
        assertThatThrownBy(() -> parser.parse("请继续补充信息", ParsedOutput.class))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    private record ParsedOutput(String decision) {
    }
}
