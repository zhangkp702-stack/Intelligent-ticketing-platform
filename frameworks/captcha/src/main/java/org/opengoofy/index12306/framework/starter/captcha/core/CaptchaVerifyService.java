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

package org.opengoofy.index12306.framework.starter.captcha.core;

/**
 * 验证码校验服务，由前端验证码组件回调得到 ticket/randstr 后，交由服务端二次核验
 */
public interface CaptchaVerifyService {

    /**
     * 校验验证码票据是否有效
     *
     * @param ticket   验证码票据
     * @param randstr  验证码随机串
     * @param clientIp 发起验证的客户端 IP
     * @return 校验是否通过
     */
    boolean verify(String ticket, String randstr, String clientIp);
}
