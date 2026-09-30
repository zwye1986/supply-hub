package com.aifuli.framework.jdvop.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

/**
 * 京东 VOP 配置。
 *
 * <p>京东开放接口参数和只读 Token Redis 参数均可通过
 * {@code jd.vop.open-api} 配置覆盖，避免与商品同步使用的旧 VOP 配置冲突。</p>
 */
@ConfigurationProperties(prefix = JdVopProperties.PREFIX)
public class JdVopProperties {

    public static final String PREFIX = "jd.vop.open-api";

    /**
     * 是否启用京东 VOP 客户端。
     */
    private boolean enabled = true;

    /**
     * 注册应用后生成的 AppKey。
     */
    private String appKey = "B040FE53C6E03D5B3996850B9FDFEBEE";

    /**
     * 注册应用后生成的 AppSecret。
     */
    private String appSecret = "d056f4b590a443c5968eba2fd96d45fb";

    /**
     * 京东开放平台 Router 地址。
     */
    private URI serverUrl = URI.create("https://api.jd.com/routerjson");

    /**
     * 京东地址详情转换 Router 地址。
     */
    private URI addressConvertUrl = URI.create("https://api.jd.com/routerjson");

    /**
     * HTTP 建连超时时间。
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * HTTP 响应超时时间。
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * 只读京东 Token Redis 配置。
     */
    private final TokenRedis tokenRedis = new TokenRedis();

    /**
     * 京东订单接口配置。
     */
    private final Order order = new Order();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public URI getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(URI serverUrl) {
        this.serverUrl = serverUrl;
    }

    public URI getAddressConvertUrl() {
        return addressConvertUrl;
    }

    public void setAddressConvertUrl(URI addressConvertUrl) {
        this.addressConvertUrl = addressConvertUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public TokenRedis getTokenRedis() {
        return tokenRedis;
    }

    public Order getOrder() {
        return order;
    }

    /**
     * 京东订单配置。
     */
    @Data
    public static class Order {

        /** 京东企业发票配置。 */
        private final Invoice invoice = new Invoice();

        /**
         * 京东企业发票配置，企业及银行信息必须由环境配置提供。
         */
        @Data
        public static class Invoice {

            /** 是否随京东订单提交企业发票信息。 */
            private boolean enabled;
            /** 发票内容类型，参考项目使用 1。 */
            private Integer contentType = 1;
            /** 发票类型，参考项目使用 24。 */
            private Integer type = 24;
            /** 发票抬头类型，企业为 5。 */
            private Integer selectedTitle = 5;
            /** 开票方式，参考项目使用 2。 */
            private Integer putType = 2;
            /** 发票公司名称。 */
            private String companyName;
            /** 收票人名称。 */
            private String name;
            /** 收票人电话。 */
            private String phone;
            /** 企业注册电话。 */
            private String regPhone;
            /** 企业注册名称。 */
            private String regCompanyName;
            /** 企业统一社会信用代码。 */
            private String regCode;
            /** 收票详细地址。 */
            private String address;
            /** 企业注册地址。 */
            private String regAddress;
            /** 企业开户银行。 */
            private String regBank;
            /** 企业银行账号。 */
            private String regBankAccount;
            /** 京东收票省编号。 */
            private Long provinceId;
            /** 京东收票市编号。 */
            private Long cityId;
            /** 京东收票区县编号。 */
            private Long countyId;
            /** 京东收票乡镇编号。 */
            private Long townId;

            /** 启用企业发票时校验全部必填配置，避免发送错误企业信息。 */
            public void validateRequiredFields() {
                if (contentType == null || type == null || selectedTitle == null || putType == null
                        || !StringUtils.hasText(companyName) || !StringUtils.hasText(name)
                        || !StringUtils.hasText(phone) || !StringUtils.hasText(regPhone)
                        || !StringUtils.hasText(regCompanyName) || !StringUtils.hasText(regCode)
                        || !StringUtils.hasText(address) || !StringUtils.hasText(regAddress)
                        || !StringUtils.hasText(regBank) || !StringUtils.hasText(regBankAccount)
                        || provinceId == null || cityId == null || countyId == null || townId == null) {
                    throw new IllegalStateException("京东企业发票配置不完整");
                }
            }
        }
    }

    /**
     * 京东 accessToken 专用 Redis 连接参数。
     */
    public static class TokenRedis {

        /** Redis 地址，可由业务配置覆盖。 */
        private String host = "47.101.155.197";

        /** Redis 端口。 */
        private int port = 6379;

        /** Redis 密码。 */
        private String password;

        /** Redis 数据库索引。 */
        private int database = 1;

        /** 京东 accessToken 的固定 Key。 */
        private String key = "jd-access-token-key:B040FE53C6E03D5B3996850B9FDFEBEE";

        /** Redis GET 命令超时时间。 */
        private Duration commandTimeout = Duration.ofSeconds(3);

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
        }
    }

}
