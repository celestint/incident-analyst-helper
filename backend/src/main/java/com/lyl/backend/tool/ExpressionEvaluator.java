package com.lyl.backend.tool;

import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 四则运算表达式求值器（calculate 工具）：LLM 返回的表达式先归一化（去空白、全角转半角）再用 exp4j 求值。
 * 非法输入抛 IllegalArgumentException，错误信息面向模型说明正确格式，经执行器回填后模型可自行修正重试。
 */
@Component
public class ExpressionEvaluator {

    /**
     * 计算结果统一保留的小数位：0-1 区间指标足够精确，同时清洗浮点运算噪音
     */
    private static final int SCALE = 4;

    /**
     * 计算结果：归一化后的表达式 + 保留 4 位小数的值
     */
    public record EvalResult(String expression, double value) {
    }

    /**
     * 求值。表达式为空、无法解析、除零/溢出均抛 IllegalArgumentException
     */
    public EvalResult eval(String rawExpression) {
        String expression = normalize(rawExpression);
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("表达式为空，请传入纯数学四则表达式，如 (100-30)/(100+50)*100");
        }
        double value;
        try {
            value = new ExpressionBuilder(expression).build().evaluate();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("表达式无法解析: " + e.getMessage()
                    + "。expression 必须是纯数学四则表达式（支持 + - * / 括号），变量必须先代入具体数值");
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("表达式计算失败: " + e.getMessage());
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("计算结果无效（除数为 0 或结果溢出），请检查表达式");
        }
        return new EvalResult(expression,
                BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).doubleValue());
    }

    /**
     * 归一化：去除所有空白字符；全角字符转半角（含全角数字/括号/运算符）；× ÷ − 映射为 * / -
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            }
            switch (c) {
                case '×' -> c = '*';
                case '÷' -> c = '/';
                case '−' -> c = '-';
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
