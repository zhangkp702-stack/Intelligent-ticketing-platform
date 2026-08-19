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

package org.opengoofy.index12306.biz.ticketservice.controller;

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.BusinessOperationStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.OrderOperationPreviewRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketPreviewRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PayInfoRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.PurchaseOperationService;
import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationCoordinator;
import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationRecoveryService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketOperationService;
import org.opengoofy.index12306.framework.starter.captcha.annotation.RiskGuard;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.ratelimiter.annotation.RateLimiter;
import org.opengoofy.index12306.framework.starter.ratelimiter.enums.RateLimitDimensionEnum;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车票控制层
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@RestController
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final PurchaseOperationService purchaseOperationService;
    private final TicketOperationService ticketOperationService;
    private final BusinessOperationCoordinator businessOperationCoordinator;
    private final BusinessOperationRecoveryService businessOperationRecoveryService;

    /**
     * 根据条件查询车票
     */
    @RateLimiter(permitsPerSecond = 5, dimension = RateLimitDimensionEnum.USER_THEN_IP, message = "查询过于频繁，请稍后再试")
    @RiskGuard(dimension = RateLimitDimensionEnum.USER_THEN_IP)
    @GetMapping("/api/ticket-service/ticket/query")
    public Result<TicketPageQueryRespDTO> pageListTicketQuery(TicketPageQueryReqDTO requestParam) {
        return Results.success(ticketService.pageListTicketQueryV1(requestParam));
    }

    /**
     * 执行 V2 购票流程；Agent 请求携带操作标识时同时提供持久化幂等保护。
     *
     * @param requestParam 购票参数以及可选的 Agent 操作标识
     * @return 新建订单，或同一操作标识已经成功时保存的原订单结果
     */
    @RateLimiter(permitsPerSecond = 3, dimension = RateLimitDimensionEnum.USER, message = "下单过于频繁，请稍后再试")
    @RiskGuard(dimension = RateLimitDimensionEnum.USER)
    @PostMapping("/api/ticket-service/ticket/purchase/v2")
    public Result<TicketPurchaseRespDTO> purchaseTicketsV2(@RequestBody PurchaseTicketReqDTO requestParam) {
        // Agent 请求先认领操作标识，普通客户端仍沿用原有 V2 购票行为。
        return Results.success(purchaseOperationService.purchaseTicketsV2(requestParam));
    }

    /**
     * 查询当前用户的异步建单结果。
     *
     * @param reservationId 购票首次响应返回的受理标识
     * @return PROCESSING、SUCCEEDED 或 FAILED，成功时同时返回订单号
     */
    @GetMapping("/api/ticket-service/ticket/purchase/status")
    public Result<TicketPurchaseStatusRespDTO> queryPurchaseStatus(
            @RequestParam(value = "reservationId") String reservationId) {
        // 状态查询只读取当前用户的 reservation，不会触发重复购票。
        return Results.success(ticketService.queryPurchaseStatus(reservationId));
    }

    /**
     * 取消车票订单；Agent 请求携带操作标识时提供持久化幂等保护。
     *
     * @param requestParam 订单号以及可选的 Agent 操作标识
     * @return 无业务响应正文
     */
    @PostMapping("/api/ticket-service/ticket/cancel")
    public Result<Void> cancelTicketOrder(@RequestBody CancelTicketOrderReqDTO requestParam) {
        // Agent 请求先认领操作标识，普通客户端仍沿用原有取消订单行为。
        ticketOperationService.cancelTicketOrder(requestParam);
        return Results.success();
    }

    /**
     * 预检查当前用户订单是否允许取消。
     *
     * @param orderSn 订单号
     * @return 当前订单可操作状态
     */
    @GetMapping("/api/ticket-service/ticket/cancel/preview")
    public Result<OrderOperationPreviewRespDTO> previewCancelTicketOrder(
            @RequestParam(value = "orderSn") String orderSn) {
        // 预览接口只读取状态，不会释放座位或修改订单。
        return Results.success(ticketService.previewCancelTicketOrder(orderSn));
    }

    /**
     * 支付单详情查询
     */
    @GetMapping("/api/ticket-service/ticket/pay/query")
    public Result<PayInfoRespDTO> getPayInfo(@RequestParam(value = "orderSn") String orderSn) {
        return Results.success(ticketService.getPayInfo(orderSn));
    }

    /**
     * 执行车票退款；Agent 请求携带操作标识时提供跨票务和支付服务的幂等保护。
     *
     * @param requestParam 退票范围、退款请求标识以及可选的 Agent 操作标识
     * @return 新建退款，或同一操作标识已经成功时保存的原退款结果
     */
    @PostMapping("/api/ticket-service/ticket/refund")
    public Result<RefundTicketRespDTO> commonTicketRefund(@RequestBody RefundTicketReqDTO requestParam) {
        // Agent 请求统一使用 operationId 认领票务操作并作为下游退款幂等键。
        return Results.success(ticketOperationService.refundTicket(requestParam));
    }

    /**
     * 查询 Agent 已确认写操作的持久化执行结果。
     *
     * @param operationId Agent 服务端生成的稳定 actionId
     * @return 当前用户可见的处理状态和脱敏结果
     */
    @GetMapping("/api/ticket-service/ticket/operations/{operationId}")
    public Result<BusinessOperationStatusRespDTO> queryBusinessOperation(
            @PathVariable String operationId) {
        // 对账只读取票务服务事实表，不会重新触发购票、取消或退票写操作。
        return Results.success(businessOperationCoordinator.getStatus(operationId));
    }

    /**
     * 为当前用户结果未知的操作立即触发一次下游权威状态查询。
     *
     * @param operationId Agent 服务端生成的稳定 actionId
     * @return 对账后的最新状态
     */
    @PostMapping("/api/ticket-service/ticket/operations/{operationId}/reconcile")
    public Result<String> reconcileBusinessOperation(@PathVariable String operationId) {
        // 接口只触发订单或退款状态查询，不会重新执行任何票务写操作。
        return Results.success(businessOperationRecoveryService.reconcileNow(operationId));
    }

    /**
     * 预览当前用户指定车票的预计退款金额。
     *
     * @param requestParam 退票范围
     * @return 不产生退款的只读预览结果
     */
    @PostMapping("/api/ticket-service/ticket/refund/preview")
    public Result<RefundTicketPreviewRespDTO> previewTicketRefund(
            @RequestBody RefundTicketReqDTO requestParam) {
        // 复用执行前校验和选票逻辑，确保展示金额与后续真实退款一致。
        return Results.success(ticketService.previewTicketRefund(requestParam));
    }
}
