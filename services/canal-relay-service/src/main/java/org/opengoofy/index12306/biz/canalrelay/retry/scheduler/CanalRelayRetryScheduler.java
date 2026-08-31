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

package org.opengoofy.index12306.biz.canalrelay.retry.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.canalrelay.config.CanalRelayProperties;
import org.opengoofy.index12306.biz.canalrelay.retry.service.CanalRelayRetryTaskService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期触发 Relay 失败任务扫描，具体抢占和发送由任务服务负责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanalRelayRetryScheduler {

    private final CanalRelayRetryTaskService retryTaskService;
    private final CanalRelayProperties properties;

    /**
     * 按固定间隔触发一轮失败任务恢复。
     */
    @Scheduled(fixedDelayString = "${index12306.canal-relay.retry-scan-interval-millis:1000}")
    public void scan() {
        if (!properties.isRetryEnabled()) {
            return;
        }
        try {
            // 扫描任务内部按数据库租约逐条处理，异常不会影响下一轮定时触发。
            retryTaskService.scanAndRetry();
        } catch (RuntimeException exception) {
            log.error("canal_relay_retry_scan_failed exceptionType={}",
                    exception.getClass().getSimpleName(), exception);
        }
    }
}
