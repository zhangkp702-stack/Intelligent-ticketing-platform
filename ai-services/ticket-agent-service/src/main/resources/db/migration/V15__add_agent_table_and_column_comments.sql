-- 为当前 Agent 正式业务表补充表注释和字段注释；Flyway 元数据表及 rollback 备份表不参与业务迁移。

-- 为 t_agent_conversation 补充字段注释
ALTER TABLE t_agent_conversation MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '会话唯一标识';
ALTER TABLE t_agent_conversation MODIFY COLUMN user_id VARCHAR(64) NOT NULL COMMENT '用户唯一标识';
ALTER TABLE t_agent_conversation MODIFY COLUMN title VARCHAR(200) COMMENT '会话标题';
ALTER TABLE t_agent_conversation MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '会话状态';
ALTER TABLE t_agent_conversation MODIFY COLUMN last_message_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '会话最后消息序号';
ALTER TABLE t_agent_conversation MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_conversation MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_conversation MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_turn 补充字段注释
ALTER TABLE t_agent_turn MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '轮次唯一标识';
ALTER TABLE t_agent_turn MODIFY COLUMN conversation_id VARCHAR(32) NOT NULL COMMENT '所属会话标识';
ALTER TABLE t_agent_turn MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '请求唯一标识';
ALTER TABLE t_agent_turn MODIFY COLUMN user_message_id VARCHAR(32) NULL COMMENT '用户消息标识';
ALTER TABLE t_agent_turn MODIFY COLUMN assistant_message_id VARCHAR(32) COMMENT '助手消息标识';
ALTER TABLE t_agent_turn MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '轮次处理状态';
ALTER TABLE t_agent_turn MODIFY COLUMN started_at TIMESTAMP(3) NULL COMMENT '轮次开始时间';
ALTER TABLE t_agent_turn MODIFY COLUMN finished_at TIMESTAMP(3) COMMENT '轮次完成时间';
ALTER TABLE t_agent_turn MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_agent_turn MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_turn MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_turn MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';
ALTER TABLE t_agent_turn MODIFY COLUMN payload_hash VARCHAR(64) COMMENT '请求参数指纹';
ALTER TABLE t_agent_turn MODIFY COLUMN submission_expires_at TIMESTAMP(3) COMMENT '提交有效期';
ALTER TABLE t_agent_turn MODIFY COLUMN lease_owner VARCHAR(128) COMMENT '执行租约持有者';
ALTER TABLE t_agent_turn MODIFY COLUMN lease_until TIMESTAMP(3) COMMENT '执行租约到期时间';
ALTER TABLE t_agent_turn MODIFY COLUMN fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '执行租约围栏令牌';
ALTER TABLE t_agent_turn MODIFY COLUMN last_heartbeat_at TIMESTAMP(3) COMMENT '最近心跳时间';
ALTER TABLE t_agent_turn MODIFY COLUMN username VARCHAR(64) COMMENT '恢复执行所需的用户名';
ALTER TABLE t_agent_turn MODIFY COLUMN last_event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已分配的 SSE 事件序号水位';
ALTER TABLE t_agent_turn MODIFY COLUMN rewrite_status VARCHAR(32) COMMENT '问题重写处理状态';
ALTER TABLE t_agent_turn MODIFY COLUMN has_rewrite BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否生成了不同于原文的问题';
ALTER TABLE t_agent_turn MODIFY COLUMN question_resolution_json TEXT COMMENT '问题重写和解析结果 JSON';
ALTER TABLE t_agent_turn MODIFY COLUMN input_consumption_status VARCHAR(32) COMMENT '用户输入消费状态';

-- 为 t_agent_message 补充字段注释
ALTER TABLE t_agent_message MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '消息唯一标识';
ALTER TABLE t_agent_message MODIFY COLUMN conversation_id VARCHAR(32) NOT NULL COMMENT '所属会话标识';
ALTER TABLE t_agent_message MODIFY COLUMN turn_id VARCHAR(32) COMMENT '所属轮次标识';
ALTER TABLE t_agent_message MODIFY COLUMN sequence_no BIGINT NOT NULL COMMENT '会话内消息序号';
ALTER TABLE t_agent_message MODIFY COLUMN role VARCHAR(32) NOT NULL COMMENT '消息角色';
ALTER TABLE t_agent_message MODIFY COLUMN message_type VARCHAR(32) NOT NULL COMMENT '消息类型';
ALTER TABLE t_agent_message MODIFY COLUMN content TEXT NOT NULL COMMENT '消息正文';
ALTER TABLE t_agent_message MODIFY COLUMN content_format VARCHAR(32) NOT NULL COMMENT '消息内容格式';
ALTER TABLE t_agent_message MODIFY COLUMN token_count INT NOT NULL DEFAULT 0 COMMENT '消息 Token 数量';
ALTER TABLE t_agent_message MODIFY COLUMN request_id VARCHAR(64) COMMENT '关联请求标识';
ALTER TABLE t_agent_message MODIFY COLUMN idempotency_key VARCHAR(128) COMMENT '消息幂等键';
ALTER TABLE t_agent_message MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_message MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_message MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_conversation_summary 补充字段注释
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '摘要唯一标识';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN conversation_id VARCHAR(32) NOT NULL COMMENT '所属会话标识';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN summary_content TEXT NOT NULL COMMENT '摘要正文';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN structured_state TEXT COMMENT '摘要对应的结构化业务状态 JSON';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN summarized_through_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '摘要已覆盖的消息序号';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN summary_version INT NOT NULL DEFAULT 0 COMMENT '摘要版本号';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN source_message_count INT NOT NULL DEFAULT 0 COMMENT '摘要来源消息数量';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN provider_id VARCHAR(64) COMMENT '摘要模型供应商标识';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN candidate_id VARCHAR(128) COMMENT '摘要模型候选标识';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN model_id VARCHAR(128) COMMENT '实际使用的摘要模型标识';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_conversation_summary MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_summary_task 补充字段注释
ALTER TABLE t_agent_summary_task MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '摘要任务唯一标识';
ALTER TABLE t_agent_summary_task MODIFY COLUMN conversation_id VARCHAR(32) NOT NULL COMMENT '所属会话标识';
ALTER TABLE t_agent_summary_task MODIFY COLUMN desired_through_sequence BIGINT NOT NULL COMMENT '期望摘要覆盖的消息序号';
ALTER TABLE t_agent_summary_task MODIFY COLUMN processing_through_sequence BIGINT COMMENT '本次正在处理的消息序号';
ALTER TABLE t_agent_summary_task MODIFY COLUMN expected_summary_version INT NOT NULL COMMENT '期望更新的摘要版本号';
ALTER TABLE t_agent_summary_task MODIFY COLUMN event_version BIGINT NOT NULL DEFAULT 1 COMMENT '摘要事件版本号';
ALTER TABLE t_agent_summary_task MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '摘要任务状态';
ALTER TABLE t_agent_summary_task MODIFY COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '当前执行尝试次数';
ALTER TABLE t_agent_summary_task MODIFY COLUMN max_attempts INT NOT NULL COMMENT '最大执行尝试次数';
ALTER TABLE t_agent_summary_task MODIFY COLUMN next_retry_at TIMESTAMP(3) COMMENT '下次重试时间';
ALTER TABLE t_agent_summary_task MODIFY COLUMN lease_owner VARCHAR(128) COMMENT '摘要任务租约持有者';
ALTER TABLE t_agent_summary_task MODIFY COLUMN lease_until TIMESTAMP(3) COMMENT '摘要任务租约到期时间';
ALTER TABLE t_agent_summary_task MODIFY COLUMN mq_message_id VARCHAR(128) COMMENT '关联 MQ 消息标识';
ALTER TABLE t_agent_summary_task MODIFY COLUMN published_at TIMESTAMP(3) COMMENT '摘要任务消息发布时间';
ALTER TABLE t_agent_summary_task MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_agent_summary_task MODIFY COLUMN failure_message VARCHAR(512) COMMENT '失败信息';
ALTER TABLE t_agent_summary_task MODIFY COLUMN started_at TIMESTAMP(3) COMMENT '任务开始时间';
ALTER TABLE t_agent_summary_task MODIFY COLUMN finished_at TIMESTAMP(3) COMMENT '任务完成时间';
ALTER TABLE t_agent_summary_task MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_summary_task MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_summary_task MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_context_snapshot 补充字段注释
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '上下文快照唯一标识';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '请求唯一标识';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN conversation_id VARCHAR(32) NOT NULL COMMENT '所属会话标识';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN summary_id VARCHAR(32) COMMENT '使用的摘要标识';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN message_from_sequence BIGINT COMMENT '加载消息起始序号';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN message_through_sequence BIGINT COMMENT '加载消息结束序号';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN selected_message_ids TEXT COMMENT '实际选中的消息标识列表 JSON';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN estimated_token_count INT NOT NULL DEFAULT 0 COMMENT '估算上下文 Token 数量';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN context_hash VARCHAR(64) NOT NULL COMMENT '上下文内容哈希';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN summary_version INT COMMENT '使用的摘要版本号';
ALTER TABLE t_agent_context_snapshot MODIFY COLUMN summarized_through_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '快照中摘要覆盖的消息序号';

-- 为 t_agent_model_call 补充字段注释
ALTER TABLE t_agent_model_call MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '模型调用唯一标识';
ALTER TABLE t_agent_model_call MODIFY COLUMN request_id VARCHAR(64) COMMENT '关联请求标识';
ALTER TABLE t_agent_model_call MODIFY COLUMN conversation_id VARCHAR(32) COMMENT '关联会话标识';
ALTER TABLE t_agent_model_call MODIFY COLUMN turn_id VARCHAR(32) COMMENT '关联轮次标识';
ALTER TABLE t_agent_model_call MODIFY COLUMN role VARCHAR(32) NOT NULL COMMENT '模型调用角色';
ALTER TABLE t_agent_model_call MODIFY COLUMN provider_id VARCHAR(64) NOT NULL COMMENT '模型供应商标识';
ALTER TABLE t_agent_model_call MODIFY COLUMN candidate_id VARCHAR(128) NOT NULL COMMENT '模型候选标识';
ALTER TABLE t_agent_model_call MODIFY COLUMN model_id VARCHAR(128) NOT NULL COMMENT '实际调用模型标识';
ALTER TABLE t_agent_model_call MODIFY COLUMN attempt_no INT NOT NULL COMMENT '本次调用尝试序号';
ALTER TABLE t_agent_model_call MODIFY COLUMN fallback_index INT NOT NULL COMMENT '后备模型路由序号';
ALTER TABLE t_agent_model_call MODIFY COLUMN outcome VARCHAR(32) NOT NULL COMMENT '模型调用结果';
ALTER TABLE t_agent_model_call MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_agent_model_call MODIFY COLUMN latency_millis BIGINT NOT NULL COMMENT '调用耗时毫秒数';
ALTER TABLE t_agent_model_call MODIFY COLUMN prompt_tokens INT COMMENT '提示词 Token 数量';
ALTER TABLE t_agent_model_call MODIFY COLUMN completion_tokens INT COMMENT '生成结果 Token 数量';
ALTER TABLE t_agent_model_call MODIFY COLUMN total_tokens INT COMMENT '总 Token 数量';
ALTER TABLE t_agent_model_call MODIFY COLUMN first_chunk_emitted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已经输出首个流式片段';
ALTER TABLE t_agent_model_call MODIFY COLUMN exception_type VARCHAR(256) COMMENT '异常类型';
ALTER TABLE t_agent_model_call MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_model_call MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_model_call MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_tool_call 补充字段注释
ALTER TABLE t_agent_tool_call MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '工具调用唯一标识';
ALTER TABLE t_agent_tool_call MODIFY COLUMN request_id VARCHAR(64) COMMENT '关联请求标识';
ALTER TABLE t_agent_tool_call MODIFY COLUMN conversation_id VARCHAR(32) COMMENT '关联会话标识';
ALTER TABLE t_agent_tool_call MODIFY COLUMN turn_id VARCHAR(32) COMMENT '关联轮次标识';
ALTER TABLE t_agent_tool_call MODIFY COLUMN tool_name VARCHAR(64) NOT NULL COMMENT '工具名称';
ALTER TABLE t_agent_tool_call MODIFY COLUMN mcp_server VARCHAR(64) NOT NULL COMMENT 'MCP 服务名称';
ALTER TABLE t_agent_tool_call MODIFY COLUMN invocation_no INT NOT NULL COMMENT '本轮工具调用序号';
ALTER TABLE t_agent_tool_call MODIFY COLUMN outcome VARCHAR(32) NOT NULL COMMENT '工具调用结果';
ALTER TABLE t_agent_tool_call MODIFY COLUMN latency_millis BIGINT NOT NULL COMMENT '调用耗时毫秒数';
ALTER TABLE t_agent_tool_call MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_agent_tool_call MODIFY COLUMN request_fingerprint VARCHAR(64) NOT NULL COMMENT '工具请求参数指纹';
ALTER TABLE t_agent_tool_call MODIFY COLUMN response_item_count INT COMMENT '工具响应项目数量';
ALTER TABLE t_agent_tool_call MODIFY COLUMN exception_type VARCHAR(256) COMMENT '异常类型';
ALTER TABLE t_agent_tool_call MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_tool_call MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_tool_call MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_workflow 补充字段注释
ALTER TABLE t_agent_workflow MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '工作流唯一标识';
ALTER TABLE t_agent_workflow MODIFY COLUMN user_id VARCHAR(64) NOT NULL COMMENT '用户唯一标识';
ALTER TABLE t_agent_workflow MODIFY COLUMN conversation_id VARCHAR(32) NOT NULL COMMENT '所属会话标识';
ALTER TABLE t_agent_workflow MODIFY COLUMN workflow_type VARCHAR(32) NOT NULL COMMENT '工作流业务类型';
ALTER TABLE t_agent_workflow MODIFY COLUMN stage VARCHAR(64) NOT NULL COMMENT '工作流当前阶段';
ALTER TABLE t_agent_workflow MODIFY COLUMN context_json TEXT NOT NULL COMMENT '工作流业务上下文 JSON';
ALTER TABLE t_agent_workflow MODIFY COLUMN expires_at TIMESTAMP(3) NOT NULL COMMENT '工作流过期时间';
ALTER TABLE t_agent_workflow MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_workflow MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_workflow MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';
ALTER TABLE t_agent_workflow MODIFY COLUMN active_scope_key VARCHAR(255) COMMENT '用户会话业务类型活动作用域键';
ALTER TABLE t_agent_workflow MODIFY COLUMN context_schema_version INT NOT NULL DEFAULT 1 COMMENT '工作流上下文结构版本号';
ALTER TABLE t_agent_workflow MODIFY COLUMN last_input_sequence BIGINT COMMENT '最近消费的输入消息序号';

-- 为 t_agent_task_execution 补充字段注释
ALTER TABLE t_agent_task_execution MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '任务执行记录唯一标识';
ALTER TABLE t_agent_task_execution MODIFY COLUMN turn_id VARCHAR(32) NOT NULL COMMENT '所属轮次标识';
ALTER TABLE t_agent_task_execution MODIFY COLUMN sequence_no INT NOT NULL COMMENT '轮次内任务序号';
ALTER TABLE t_agent_task_execution MODIFY COLUMN intent VARCHAR(64) NOT NULL COMMENT '任务意图';
ALTER TABLE t_agent_task_execution MODIFY COLUMN plan_json TEXT NOT NULL COMMENT '任务计划 JSON';
ALTER TABLE t_agent_task_execution MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '任务执行状态';
ALTER TABLE t_agent_task_execution MODIFY COLUMN result_json TEXT COMMENT '任务执行结果 JSON';
ALTER TABLE t_agent_task_execution MODIFY COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '执行尝试次数';
ALTER TABLE t_agent_task_execution MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_agent_task_execution MODIFY COLUMN started_at TIMESTAMP(3) COMMENT '任务开始时间';
ALTER TABLE t_agent_task_execution MODIFY COLUMN finished_at TIMESTAMP(3) COMMENT '任务完成时间';
ALTER TABLE t_agent_task_execution MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_task_execution MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_task_execution MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_action_draft 补充字段注释
ALTER TABLE t_agent_action_draft MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '动作草案唯一标识';
ALTER TABLE t_agent_action_draft MODIFY COLUMN user_id VARCHAR(64) NOT NULL COMMENT '用户唯一标识';
ALTER TABLE t_agent_action_draft MODIFY COLUMN conversation_id VARCHAR(32) NOT NULL COMMENT '所属会话标识';
ALTER TABLE t_agent_action_draft MODIFY COLUMN turn_id VARCHAR(32) NOT NULL COMMENT '创建草案的轮次标识';
ALTER TABLE t_agent_action_draft MODIFY COLUMN action_type VARCHAR(32) NOT NULL COMMENT '动作业务类型';
ALTER TABLE t_agent_action_draft MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '动作草案状态';
ALTER TABLE t_agent_action_draft MODIFY COLUMN payload_json TEXT NOT NULL COMMENT '待执行动作参数 JSON';
ALTER TABLE t_agent_action_draft MODIFY COLUMN payload_hash VARCHAR(64) NOT NULL COMMENT '动作参数指纹';
ALTER TABLE t_agent_action_draft MODIFY COLUMN confirmation_expires_at TIMESTAMP(3) NOT NULL COMMENT '确认令牌过期时间';
ALTER TABLE t_agent_action_draft MODIFY COLUMN confirmation_consumed_at TIMESTAMP(3) COMMENT '确认令牌消费时间';
ALTER TABLE t_agent_action_draft MODIFY COLUMN execution_id VARCHAR(32) COMMENT '旧版动作执行记录标识';
ALTER TABLE t_agent_action_draft MODIFY COLUMN result_json TEXT COMMENT '动作执行结果 JSON';
ALTER TABLE t_agent_action_draft MODIFY COLUMN result_reference VARCHAR(128) COMMENT '动作结果引用标识';
ALTER TABLE t_agent_action_draft MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_agent_action_draft MODIFY COLUMN finished_at TIMESTAMP(3) COMMENT '动作完成时间';
ALTER TABLE t_agent_action_draft MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_action_draft MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_action_draft MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_action_execution 补充字段注释
ALTER TABLE t_agent_action_execution MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '旧版动作执行记录唯一标识';
ALTER TABLE t_agent_action_execution MODIFY COLUMN action_id VARCHAR(32) NOT NULL COMMENT '关联动作草案标识';
ALTER TABLE t_agent_action_execution MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '执行请求唯一标识';
ALTER TABLE t_agent_action_execution MODIFY COLUMN idempotency_key VARCHAR(128) NOT NULL COMMENT '执行幂等键';
ALTER TABLE t_agent_action_execution MODIFY COLUMN outcome VARCHAR(32) NOT NULL COMMENT '动作执行结果状态';
ALTER TABLE t_agent_action_execution MODIFY COLUMN started_at TIMESTAMP(3) NOT NULL COMMENT '执行开始时间';
ALTER TABLE t_agent_action_execution MODIFY COLUMN finished_at TIMESTAMP(3) COMMENT '执行完成时间';
ALTER TABLE t_agent_action_execution MODIFY COLUMN result_reference VARCHAR(128) COMMENT '执行结果引用标识';
ALTER TABLE t_agent_action_execution MODIFY COLUMN response_fingerprint VARCHAR(64) COMMENT '下游响应指纹';
ALTER TABLE t_agent_action_execution MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_agent_action_execution MODIFY COLUMN exception_type VARCHAR(256) COMMENT '异常类型';
ALTER TABLE t_agent_action_execution MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_action_execution MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_action_execution MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';
ALTER TABLE t_agent_action_execution MODIFY COLUMN lease_owner VARCHAR(128) COMMENT '旧版执行租约持有者';
ALTER TABLE t_agent_action_execution MODIFY COLUMN lease_until TIMESTAMP(3) COMMENT '旧版执行租约到期时间';
ALTER TABLE t_agent_action_execution MODIFY COLUMN fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '旧版执行租约围栏令牌';
ALTER TABLE t_agent_action_execution MODIFY COLUMN last_heartbeat_at TIMESTAMP(3) COMMENT '旧版执行最近心跳时间';
ALTER TABLE t_agent_action_execution MODIFY COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '执行尝试次数';
ALTER TABLE t_agent_action_execution MODIFY COLUMN next_reconcile_at TIMESTAMP(3) COMMENT '下次对账时间';

-- 为 t_agent_action_reconciliation 补充字段注释
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '旧版对账事件唯一标识';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN action_id VARCHAR(32) NOT NULL COMMENT '关联动作草案标识';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN event_version BIGINT NOT NULL DEFAULT 1 COMMENT '对账事件版本号';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '旧版对账状态';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '对账尝试次数';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN max_attempts INT NOT NULL COMMENT '最大对账尝试次数';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN next_retry_at TIMESTAMP(3) COMMENT '下次重试时间';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN lease_owner VARCHAR(128) COMMENT '旧版对账租约持有者';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN lease_until TIMESTAMP(3) COMMENT '旧版对账租约到期时间';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN consumer_name VARCHAR(128) COMMENT '旧版消费者名称';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN mq_message_id VARCHAR(128) COMMENT '关联 MQ 消息标识';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN published_at TIMESTAMP(3) COMMENT '消息发布时间';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN failure_message VARCHAR(512) COMMENT '失败信息';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN started_at TIMESTAMP(3) COMMENT '对账开始时间';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN finished_at TIMESTAMP(3) COMMENT '对账完成时间';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_action_reconciliation MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_agent_stream_event 补充字段注释
ALTER TABLE t_agent_stream_event MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '流式事件唯一标识';
ALTER TABLE t_agent_stream_event MODIFY COLUMN turn_id VARCHAR(32) NOT NULL COMMENT '所属轮次标识';
ALTER TABLE t_agent_stream_event MODIFY COLUMN event_sequence BIGINT NOT NULL COMMENT '轮次内事件序号';
ALTER TABLE t_agent_stream_event MODIFY COLUMN event_type VARCHAR(32) NOT NULL COMMENT '流式事件类型';
ALTER TABLE t_agent_stream_event MODIFY COLUMN payload_json TEXT NOT NULL COMMENT '事件载荷 JSON';
ALTER TABLE t_agent_stream_event MODIFY COLUMN terminal BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为终态事件';
ALTER TABLE t_agent_stream_event MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE t_agent_stream_event MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_agent_stream_event MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_reliable_command 补充字段注释
ALTER TABLE t_reliable_command MODIFY COLUMN routing_key VARCHAR(128) NOT NULL COMMENT '命令路由键';
ALTER TABLE t_reliable_command MODIFY COLUMN namespace VARCHAR(64) NOT NULL COMMENT '可靠命令命名空间';
ALTER TABLE t_reliable_command MODIFY COLUMN command_id VARCHAR(128) NOT NULL COMMENT '命令唯一标识';
ALTER TABLE t_reliable_command MODIFY COLUMN command_type VARCHAR(64) NOT NULL COMMENT '命令业务类型';
ALTER TABLE t_reliable_command MODIFY COLUMN execution_mode VARCHAR(32) NOT NULL COMMENT '命令执行模式';
ALTER TABLE t_reliable_command MODIFY COLUMN owner_id VARCHAR(128) NOT NULL COMMENT '业务所有者标识';
ALTER TABLE t_reliable_command MODIFY COLUMN request_fingerprint VARCHAR(128) NOT NULL COMMENT '请求参数指纹';
ALTER TABLE t_reliable_command MODIFY COLUMN fingerprint_version VARCHAR(32) NOT NULL COMMENT '参数指纹版本';
ALTER TABLE t_reliable_command MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '可靠命令状态';
ALTER TABLE t_reliable_command MODIFY COLUMN result_payload TEXT COMMENT '命令结果载荷';
ALTER TABLE t_reliable_command MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_reliable_command MODIFY COLUMN failure_message VARCHAR(512) COMMENT '失败信息';
ALTER TABLE t_reliable_command MODIFY COLUMN business_reference VARCHAR(256) COMMENT '下游业务结果引用';
ALTER TABLE t_reliable_command MODIFY COLUMN lease_owner VARCHAR(128) COMMENT '当前执行租约持有者';
ALTER TABLE t_reliable_command MODIFY COLUMN lease_until TIMESTAMP(3) COMMENT '当前执行租约到期时间';
ALTER TABLE t_reliable_command MODIFY COLUMN fencing_token BIGINT NOT NULL DEFAULT 1 COMMENT '执行租约围栏令牌';
ALTER TABLE t_reliable_command MODIFY COLUMN last_heartbeat_at TIMESTAMP(3) COMMENT '最近心跳时间';
ALTER TABLE t_reliable_command MODIFY COLUMN attempt_count INT NOT NULL DEFAULT 1 COMMENT '执行尝试次数';
ALTER TABLE t_reliable_command MODIFY COLUMN next_reconcile_at TIMESTAMP(3) COMMENT '下次对账时间';
ALTER TABLE t_reliable_command MODIFY COLUMN reconcile_attempt_count INT NOT NULL DEFAULT 0 COMMENT '对账尝试次数';
ALTER TABLE t_reliable_command MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_reliable_command MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_reliable_command_audit 补充字段注释
ALTER TABLE t_reliable_command_audit MODIFY COLUMN id BIGINT AUTO_INCREMENT NOT NULL COMMENT '审计记录唯一标识';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN routing_key VARCHAR(128) NOT NULL COMMENT '命令路由键';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN namespace VARCHAR(64) NOT NULL COMMENT '可靠命令命名空间';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN command_id VARCHAR(128) NOT NULL COMMENT '命令唯一标识';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN operator_id VARCHAR(128) NOT NULL COMMENT '状态变更操作者标识';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN old_status VARCHAR(32) COMMENT '变更前状态';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN new_status VARCHAR(32) NOT NULL COMMENT '变更后状态';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN reason VARCHAR(128) NOT NULL COMMENT '状态变更原因';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN evidence VARCHAR(512) COMMENT '状态变更证据';
ALTER TABLE t_reliable_command_audit MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';

-- 为 t_reliable_outbox_event 补充字段注释
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN namespace VARCHAR(64) NOT NULL COMMENT '可靠事件命名空间';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN event_id VARCHAR(128) NOT NULL COMMENT '事件唯一标识';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN deduplication_key VARCHAR(128) NOT NULL COMMENT '事件去重键';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN event_type VARCHAR(64) NOT NULL COMMENT '事件类型';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN aggregate_id VARCHAR(128) NOT NULL COMMENT '关联聚合标识';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN payload TEXT NOT NULL COMMENT '事件载荷';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN event_version BIGINT NOT NULL COMMENT '事件版本号';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT 'Outbox 发布状态';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN next_publish_at TIMESTAMP(3) NOT NULL COMMENT '下次发布时间';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN publish_owner VARCHAR(128) COMMENT '发布租约持有者';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN publish_lease_until TIMESTAMP(3) COMMENT '发布租约到期时间';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN publish_fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '发布租约围栏令牌';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN publish_attempt_count INT NOT NULL DEFAULT 0 COMMENT '发布尝试次数';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN broker_message_id VARCHAR(128) COMMENT 'Broker 消息标识';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN published_at TIMESTAMP(3) COMMENT '实际发布时间';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN failure_message VARCHAR(512) COMMENT '失败信息';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_reliable_outbox_event MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';

-- 为 t_reliable_inbox_consumption 补充字段注释
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN namespace VARCHAR(64) NOT NULL COMMENT '可靠事件命名空间';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN event_id VARCHAR(128) NOT NULL COMMENT '事件唯一标识';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN consumer_name VARCHAR(128) NOT NULL COMMENT '消费者名称';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN event_version BIGINT NOT NULL COMMENT '消费事件版本号';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT 'Inbox 消费状态';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN attempt_count INT NOT NULL COMMENT '消费尝试次数';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN max_attempts INT NOT NULL COMMENT '最大消费尝试次数';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN next_retry_at TIMESTAMP(3) COMMENT '下次消费重试时间';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN lease_owner VARCHAR(128) COMMENT '消费租约持有者';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN lease_until TIMESTAMP(3) COMMENT '消费租约到期时间';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '消费租约围栏令牌';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN failure_category VARCHAR(64) COMMENT '失败分类';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN failure_message VARCHAR(512) COMMENT '失败信息';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN started_at TIMESTAMP(3) COMMENT '消费开始时间';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN finished_at TIMESTAMP(3) COMMENT '消费完成时间';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL COMMENT '创建时间';
ALTER TABLE t_reliable_inbox_consumption MODIFY COLUMN updated_at TIMESTAMP(3) NOT NULL COMMENT '最后更新时间';
