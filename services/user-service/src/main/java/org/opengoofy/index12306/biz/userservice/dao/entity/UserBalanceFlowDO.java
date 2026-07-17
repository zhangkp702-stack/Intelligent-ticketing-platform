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

package org.opengoofy.index12306.biz.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.opengoofy.index12306.framework.starter.database.base.BaseDO;

/**
 * 用户余额变动流水，负责记录支付扣款和退票退款。
 */
@Data
@TableName("t_user_balance_flow")
public class UserBalanceFlowDO extends BaseDO {

    /**
     * 主键
     */
    private Long id;

    /**
     * 用户名，同时作为分片键
     */
    private String username;

    /**
     * 业务幂等号
     */
    private String bizNo;

    /**
     * 业务类型：0 支付扣款，1 退款入账
     */
    private Integer bizType;

    /**
     * 变动金额，单位为分
     */
    private Long amount;

    /**
     * 变动前余额，单位为分
     */
    private Long balanceBefore;

    /**
     * 变动后余额，单位为分
     */
    private Long balanceAfter;

    /**
     * 处理状态：0 处理中，1 成功
     */
    private Integer status;
}
