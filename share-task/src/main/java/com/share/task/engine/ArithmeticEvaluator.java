package com.share.task.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 算术表达式求值器（FR-05 自定义加工扩展）：支持 + - * / 与括号、字段名、数字常量。
 * <p>示例：{@code salary * 12}、{@code (a + b) / 2}。</p>
 */
public final class ArithmeticEvaluator {

    private ArithmeticEvaluator() {
    }

    /** 按行数据求值；表达式非法或字段缺失时返回 null。 */
    public static BigDecimal evaluate(String expression, Map<String, Object> row) {
        try {
            Parser parser = new Parser(tokenize(expression), row);
            BigDecimal value = parser.parseExpression();
            return parser.isEnd() ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || c == '.') {
                int start = i;
                while (i < expression.length() && (Character.isDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(expression.substring(start, i));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < expression.length()
                        && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(expression.substring(start, i));
            } else if ("+-*/(),".indexOf(c) >= 0) {
                tokens.add(String.valueOf(c));
                i++;
            } else {
                throw new IllegalArgumentException("非法字符: " + c);
            }
        }
        return tokens;
    }

    /** 递归下降解析器。 */
    private static final class Parser {

        private final List<String> tokens;
        private final Map<String, Object> row;
        private int pos;

        private Parser(List<String> tokens, Map<String, Object> row) {
            this.tokens = tokens;
            this.row = row;
        }

        private boolean isEnd() {
            return pos >= tokens.size();
        }

        private String peek() {
            return isEnd() ? null : tokens.get(pos);
        }

        private BigDecimal parseExpression() {
            BigDecimal value = parseTerm();
            while (true) {
                String token = peek();
                if ("+".equals(token)) {
                    pos++;
                    value = value.add(parseTerm());
                } else if ("-".equals(token)) {
                    pos++;
                    value = value.subtract(parseTerm());
                } else {
                    return value;
                }
            }
        }

        private BigDecimal parseTerm() {
            BigDecimal value = parseFactor();
            while (true) {
                String token = peek();
                if ("*".equals(token)) {
                    pos++;
                    value = value.multiply(parseFactor());
                } else if ("/".equals(token)) {
                    pos++;
                    BigDecimal divisor = parseFactor();
                    if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                        throw new ArithmeticException("除数为 0");
                    }
                    value = value.divide(divisor, 6, java.math.RoundingMode.HALF_UP);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal parseFactor() {
            String token = peek();
            if (token == null) {
                throw new IllegalArgumentException("表达式不完整");
            }
            if ("-".equals(token)) {
                pos++;
                return parseFactor().negate();
            }
            if ("(".equals(token)) {
                pos++;
                BigDecimal value = parseExpression();
                if (!")".equals(peek())) {
                    throw new IllegalArgumentException("缺少右括号");
                }
                pos++;
                return value;
            }
            pos++;
            if (isNumber(token)) {
                return new BigDecimal(token);
            }
            if (row != null) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(token)) {
                        Object value = entry.getValue();
                        if (value == null || String.valueOf(value).isBlank()) {
                            return BigDecimal.ZERO;
                        }
                        return new BigDecimal(String.valueOf(value).trim());
                    }
                }
            }
            return BigDecimal.ZERO;
        }

        private boolean isNumber(String token) {
            try {
                new BigDecimal(token);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
