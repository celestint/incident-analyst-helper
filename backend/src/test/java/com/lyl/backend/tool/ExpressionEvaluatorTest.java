package com.lyl.backend.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ExpressionEvaluator 纯单元测试：四则/括号/空格/全角符号/除零/非法表达式/浮点噪音/0-1 区间精度
 */
class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    @Test
    void evalBasicArithmetic() {
        assertEquals(46.6667, evaluator.eval("(100-30)/(100+50)*100").value());
        assertEquals(7.0, evaluator.eval("1+2*3").value());
        assertEquals(3.0, evaluator.eval("((2+3)*(4-1))/5").value());
    }

    @Test
    void evalToleratesWhitespace() {
        ExpressionEvaluator.EvalResult result = evaluator.eval("  (100 - 30) / (100 + 50) * 100 ");
        assertEquals("(100-30)/(100+50)*100", result.expression());
        assertEquals(46.6667, result.value());
    }

    @Test
    void evalNormalizesFullWidthChars() {
        // 全角数字/括号/运算符 + ×号
        assertEquals(46.6667, evaluator.eval("（１００－３０）／（１００＋５０）×１００").value());
        // ÷ 与全角小数点
        assertEquals(2.5, evaluator.eval("５÷２").value());
    }

    @Test
    void evalCleansFloatingNoise() {
        assertEquals(0.3, evaluator.eval("0.1+0.2").value());
        assertEquals(12.5, evaluator.eval("12.500000000000002/1").value());
    }

    @Test
    void evalKeepsPrecisionInRangeZeroToOne() {
        assertEquals(0.3333, evaluator.eval("1/3").value());
        assertEquals(0.625, evaluator.eval("5/8").value());
        assertEquals(0.8765, evaluator.eval("0.87654").value());
    }

    @Test
    void evalReturnsNormalizedExpression() {
        assertEquals("(100-30)/(100+50)*100", evaluator.eval("(100-30)/(100+50)*100").expression());
    }

    @Test
    void evalRejectsDivisionByZero() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.eval("1/0"));
        assertThrows(IllegalArgumentException.class, () -> evaluator.eval("0/0"));
    }

    @Test
    void evalRejectsInvalidExpressions() {
        // 未代入数值的变量名
        assertThrows(IllegalArgumentException.class, () -> evaluator.eval("UserMem-30"));
        // 非数学内容
        assertThrows(IllegalArgumentException.class, () -> evaluator.eval("hello"));
        // 括号不匹配
        assertThrows(IllegalArgumentException.class, () -> evaluator.eval("(2+3"));
        // 空与 null
        assertThrows(IllegalArgumentException.class, () -> evaluator.eval("  "));
        assertThrows(IllegalArgumentException.class, () -> evaluator.eval(null));
    }
}
