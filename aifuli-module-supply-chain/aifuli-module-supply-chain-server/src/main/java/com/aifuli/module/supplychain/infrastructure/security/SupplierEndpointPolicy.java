package com.aifuli.module.supplychain.infrastructure.security;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 发布期供应商 URL 白名单与基础 SSRF 防护。运行期仍须使用受控 DNS/出口。 */
@Component
public class SupplierEndpointPolicy {

    private final Set<String> allowedHosts;

    public SupplierEndpointPolicy(Environment environment) {
        String configured = environment.getProperty("aifuli.supply-chain.connector.allowed-hosts", "");
        this.allowedHosts = Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Supplier base URL is invalid", exception);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isBlank()
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Supplier base URL must be a plain HTTPS origin/path");
        }
        if (isLocalOrPrivateLiteral(host)) {
            throw new IllegalArgumentException("Supplier base URL cannot target a local or private address");
        }
        if (allowedHosts.isEmpty() || allowedHosts.stream().noneMatch(
                allowed -> host.equals(allowed) || host.endsWith("." + allowed))) {
            throw new IllegalArgumentException("Supplier base URL host is not in the outbound allowlist");
        }
    }

    private static boolean isLocalOrPrivateLiteral(String host) {
        if (host.equals("localhost") || host.equals("0.0.0.0") || host.equals("::1")
                || host.equals("169.254.169.254")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return host.contains(":");
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 10 || first == 127 || first == 0 || first >= 224
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException ignored) {
            return true;
        }
    }
}
