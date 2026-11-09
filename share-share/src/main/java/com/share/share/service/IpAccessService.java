package com.share.share.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关来源 IP 访问控制（FR-07）。
 * <p>支持精确 IP、通配符（如 {@code 192.168.43.*}）与 CIDR（IPv4/IPv6，如 {@code 10.0.0.0/8}）。
 * 黑名单优先于白名单；白名单为空表示不限制来源。</p>
 */
@Slf4j
@Service
public class IpAccessService {

    /** 判断来源 IP 是否放行。 */
    public boolean allowed(String ip, String whitelist, String blacklist) {
        String source = ip == null ? "" : ip.trim();
        if (!source.isEmpty() && matchesAny(blacklist, source)) {
            return false;
        }
        if (!StringUtils.hasText(whitelist)) {
            return true;
        }
        return matchesAny(whitelist, source);
    }

    /** 规则集合中是否命中任意一条。 */
    public boolean matchesAny(String rules, String ip) {
        if (!StringUtils.hasText(rules) || !StringUtils.hasText(ip)) {
            return false;
        }
        for (String rule : splitRules(rules)) {
            if (match(rule, ip)) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitRules(String rules) {
        List<String> list = new ArrayList<>();
        for (String part : rules.split("[,;\\s]+")) {
            String rule = part.trim();
            if (!rule.isEmpty()) {
                list.add(rule);
            }
        }
        return list;
    }

    private boolean match(String rule, String ip) {
        if ("*".equals(rule) || "0.0.0.0/0".equals(rule) || "::/0".equals(rule)) {
            return true;
        }
        if (rule.contains("/")) {
            return matchCidr(rule, ip);
        }
        if (rule.contains("*")) {
            return matchWildcard(rule, ip);
        }
        return rule.equalsIgnoreCase(ip);
    }

    private boolean matchWildcard(String rule, String ip) {
        String[] r = rule.split("\\.");
        String[] s = ip.split("\\.");
        if (r.length != s.length) {
            return false;
        }
        for (int i = 0; i < r.length; i++) {
            if (!"*".equals(r[i]) && !r[i].equalsIgnoreCase(s[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean matchCidr(String cidr, String ip) {
        try {
            int slash = cidr.indexOf('/');
            String network = cidr.substring(0, slash);
            int prefix = Integer.parseInt(cidr.substring(slash + 1));
            byte[] networkBytes = InetAddress.getByName(network).getAddress();
            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            if (networkBytes.length != ipBytes.length || prefix < 0 || prefix > networkBytes.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != ipBytes[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits) & 0xFF;
            return (networkBytes[fullBytes] & mask) == (ipBytes[fullBytes] & mask);
        } catch (Exception e) {
            log.warn("非法的 IP 访问规则：{}（{}）", cidr, e.getMessage());
            return false;
        }
    }
}
