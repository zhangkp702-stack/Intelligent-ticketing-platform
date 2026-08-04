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

package org.opengoofy.index12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.framework.starter.database.base.BaseDO;

/**
 * 保存业务操作自动恢复和人工处置的不可变审计记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_business_operation_audit")
public class BusinessOperationAuditDO extends BaseDO {

    /**
     * 审计记录标识。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 被处置的业务操作标识。
     */
    private String operationId;

    /**
     * 自动恢复器或人工操作人标识。
     */
    private String operatorId;

    /**
     * 状态迁移前状态。
     */
    private Integer oldStatus;

    /**
     * 状态迁移后状态。
     */
    private Integer newStatus;

    /**
     * 状态迁移原因。
     */
    private String reason;

    /**
     * 下游命令状态等安全证据摘要。
     */
    private String evidence;
}
