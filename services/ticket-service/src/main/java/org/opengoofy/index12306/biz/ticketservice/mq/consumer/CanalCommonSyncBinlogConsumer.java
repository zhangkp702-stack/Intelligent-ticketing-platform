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

package org.opengoofy.index12306.biz.ticketservice.mq.consumer;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;
import org.opengoofy.index12306.biz.ticketservice.common.enums.CanalExecuteStrategyMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.mq.event.CanalBinlogEvent;
import org.opengoofy.index12306.biz.ticketservice.service.OrderCloseRollbackService;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 列车车票余量缓存更新消费端
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = TicketRocketMQConstant.CANAL_COMMON_SYNC_TOPIC_KEY,
        consumerGroup = TicketRocketMQConstant.CANAL_COMMON_SYNC_CG_KEY
)
public class CanalCommonSyncBinlogConsumer implements RocketMQListener<CanalBinlogEvent> {

    private final AbstractStrategyChoose abstractStrategyChoose;
    private final OrderCloseRollbackService orderCloseRollbackService;

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;

    /**
     * 消费 Canal 的已提交订单变更；订单关闭释放不依赖余票缓存更新模式，其他 Binlog 缓存策略仍按原配置执行。
     *
     * @param message Canal 投递的数据库变更消息
     */
    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:binlog_sync:",
            key = "#message.getId()+'_'+#message.hashCode()",
            type = IdempotentTypeEnum.SPEL,
            scene = IdempotentSceneEnum.MQ,
            keyTimeout = 7200L
    )
    @Override
    public void onMessage(CanalBinlogEvent message) {
        // 余票 Binlog 更新延迟问题如何解决？详情查看：https://nageoffer.com/12306/question
        if (message.getIsDdl() || CollUtil.isEmpty(message.getOld()) || !Objects.equals("UPDATE", message.getType())) {
            return;
        }
        // 已提交的订单关闭 Binlog 相当于当前架构中的事务事件来源，始终创建/恢复 reservation 释放命令。
        triggerOrderCloseRollback(message);
        if (!StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")) {
            return;
        }
        abstractStrategyChoose.chooseAndExecute(
                message.getTable(),
                message,
                CanalExecuteStrategyMarkEnum.isPatternMatch(message.getTable())
        );
    }

    /**
     * 从订单关闭 Binlog 中提取订单号并触发可靠释放命令。
     *
     * @param message Canal 投递的数据库变更消息
     */
    private void triggerOrderCloseRollback(CanalBinlogEvent message) {
        // 仅处理主订单表，避免 t_order_item 等分片表变更被误认为关闭命令。
        if (!message.getTable().matches("t_order(_\\d+)?")) {
            return;
        }
        List<Map<String, Object>> closedOrders = message.getData().stream()
                .filter(each -> Objects.equals("30", String.valueOf(each.get("status"))))
                .toList();
        // Canal 至少一次投递，由 reservation 状态与可靠命令共同吸收重复消息。
        closedOrders.forEach(each -> orderCloseRollbackService.rollback(String.valueOf(each.get("order_sn"))));
    }
}
