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

package org.opengoofy.index12306.biz.userservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.userservice.dto.req.BalanceChangeReqDTO;
import org.opengoofy.index12306.biz.userservice.dto.resp.UserBalanceRespDTO;
import org.opengoofy.index12306.biz.userservice.service.UserBalanceService;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户余额控制层。
 */
@RestController
@RequiredArgsConstructor
public class UserBalanceController {

    private final UserBalanceService userBalanceService;

    /**
     * 查询当前登录用户余额。
     *
     * @return 当前可用余额
     */
    @GetMapping("/api/user-service/balance")
    public Result<UserBalanceRespDTO> queryBalance() {
        // 公开入口只允许读取当前用户余额。
        return Results.success(userBalanceService.queryBalance());
    }

    /**
     * 为内部支付服务扣减当前用户余额。
     *
     * @param requestParam 支付业务号和金额
     * @return 扣款后的余额
     */
    @PostMapping("/internal/user-service/balance/debit")
    public Result<UserBalanceRespDTO> debit(@RequestBody @Valid BalanceChangeReqDTO requestParam) {
        // 内部接口不经过网关，由支付服务携带已验证的用户上下文调用。
        return Results.success(userBalanceService.debit(requestParam));
    }

    /**
     * 为内部退款服务退回当前用户余额。
     *
     * @param requestParam 退款业务号和金额
     * @return 退款后的余额
     */
    @PostMapping("/internal/user-service/balance/credit")
    public Result<UserBalanceRespDTO> credit(@RequestBody @Valid BalanceChangeReqDTO requestParam) {
        // 退款入账复用同一余额流水机制，避免重复退回。
        return Results.success(userBalanceService.credit(requestParam));
    }
}
