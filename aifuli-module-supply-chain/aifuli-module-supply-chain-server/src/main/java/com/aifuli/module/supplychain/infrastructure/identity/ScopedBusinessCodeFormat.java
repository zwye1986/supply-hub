package com.aifuli.module.supplychain.infrastructure.identity;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 供应链七类主数据的三位永久编码格式。只处理格式，不负责号码占用和归属查询。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#2-编码结构与容量}。
 */
public final class ScopedBusinessCodeFormat {

    public static final int MIN_NO = 1;
    public static final int MAX_NO = 999;
    private static final Pattern THREE_DIGITS = Pattern.compile("[0-9]{3}");

    /** 已通过子编码的结构校验，但用户提交的父段与所选父对象不一致。 */
    public static final class ParentMismatchException extends IllegalArgumentException {
        public ParentMismatchException(String message) {
            super(message);
        }
    }

    private ScopedBusinessCodeFormat() {
    }

    public static String root(String prefix, int number) {
        return prefix + "_" + segment(number);
    }

    public static String child(String prefix, int parentNumber, int number) {
        return prefix + "_" + segment(parentNumber) + "_" + segment(number);
    }

    public static int parseRoot(String prefix, String code) {
        Integer preferred = preferredRoot(prefix, code);
        if (preferred == null) {
            throw new IllegalArgumentException(rootHint(prefix));
        }
        return preferred;
    }

    /** 旧编码可能是 ULID 或历史手填值；不符合新根格式时正常返回空，不靠异常分支。 */
    static Integer preferredRoot(String prefix, String code) {
        String expectedPrefix = prefix + "_";
        if (code == null || !code.startsWith(expectedPrefix)) {
            return null;
        }
        String suffix = code.substring(expectedPrefix.length());
        if (!THREE_DIGITS.matcher(suffix).matches() || "000".equals(suffix)) {
            return null;
        }
        return Integer.parseInt(suffix);
    }

    public static int parseChild(String prefix, int parentNumber, String code) {
        String expectedPrefix = prefix + "_";
        if (code == null || !code.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException(childHint(prefix, parentNumber));
        }
        String suffix = code.substring(expectedPrefix.length());
        Matcher matcher = Pattern.compile("([0-9]{3})_([0-9]{3})").matcher(suffix);
        if (!matcher.matches() || "000".equals(matcher.group(2))) {
            throw new IllegalArgumentException(childHint(prefix, parentNumber));
        }
        if (!matcher.group(1).equals(segment(parentNumber))) {
            throw new ParentMismatchException("编码 " + code + " 的父段 " + matcher.group(1)
                    + " 与所选父对象的内部短序号 " + segment(parentNumber) + " 不一致；"
                    + childHint(prefix, parentNumber));
        }
        return Integer.parseInt(matcher.group(2));
    }

    static String segment(int number) {
        if (number < MIN_NO || number > MAX_NO) {
            throw new IllegalArgumentException("三位流水号范围为 001–999");
        }
        return String.format(Locale.ROOT, "%03d", number);
    }

    private static String rootHint(String prefix) {
        return "编码应为 " + root(prefix, 1) + " 格式，流水号范围 001–999";
    }

    private static String childHint(String prefix, int parentNumber) {
        return "编码应为 " + child(prefix, parentNumber, 1) + " 格式，子段范围 001–999";
    }
}
