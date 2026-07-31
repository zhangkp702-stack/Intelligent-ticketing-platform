package org.opengoofy.index12306.ai.agentservice.infra.database;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 MyBatis-Plus 持久化能力，保留智能体会话、工作流和动作草稿的乐观锁语义。
 */
@Configuration
public class MybatisPlusConfiguration {

    /**
     * 注册乐观锁拦截器，使实体版本字段在更新时继续参与并发冲突检测。
     *
     * @return 已启用乐观锁的 MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 版本校验避免两个并发请求覆盖同一会话、工作流或确认草稿的状态推进结果。
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
