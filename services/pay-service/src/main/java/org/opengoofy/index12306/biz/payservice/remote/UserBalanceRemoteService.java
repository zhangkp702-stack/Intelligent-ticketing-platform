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

package org.opengoofy.index12306.biz.payservice.remote;

import org.opengoofy.index12306.biz.payservice.remote.dto.BalanceChangeReqDTO;
import org.opengoofy.index12306.biz.payservice.remote.dto.UserBalanceRespDTO;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 用户余额远程服务。
 */
@FeignClient(value = "index12306-user${unique-name:}-service", url = "${aggregation.remote-url:}")
public interface UserBalanceRemoteService {

    /**
     * 查询当前用户余额。
     *
     * @return 当前余额
     */
    @GetMapping("/api/user-service/balance")
    Result<UserBalanceRespDTO> queryBalance();

    /**
     * 幂等扣减当前用户余额。
     *
     * @param requestParam 支付业务号与金额
     * @return 扣款后的余额
     */
    @PostMapping("/internal/user-service/balance/debit")
    Result<UserBalanceRespDTO> debit(@RequestBody BalanceChangeReqDTO requestParam);

    /**
     * 幂等退回当前用户余额。
     *
     * @param requestParam 退款业务号与金额
     * @return 退款后的余额
     */
    @PostMapping("/internal/user-service/balance/credit")
    Result<UserBalanceRespDTO> credit(@RequestBody BalanceChangeReqDTO requestParam);
}
