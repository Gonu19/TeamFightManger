package com.teamfighter.tfm.common;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 정규형 JSON 직렬화.
 *
 * <p>골든 파일의 계약은 <b>바이트 일치</b>다. 그래서 표현이 흔들릴 여지를 모두 없앤다 —
 * 키는 정렬, 구분자에 공백 없음, 비ASCII 는 이스케이프하지 않고 그대로.
 * Python 의 {@code json.dumps(sort_keys=True, ensure_ascii=False, separators=(",", ":"))}
 * 와 같은 출력을 낸다.
 *
 * <p>범용 JSON 라이브러리를 쓰지 않는 이유는 그 라이브러리의 기본값이 바뀌면
 * 계약이 조용히 깨지기 때문이다. 여기서는 규칙이 코드에 보인다.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(1 << 16);
        append(sb, value);
        return sb.toString();
    }

    /** 키가 항상 정렬되도록 강제한다. */
    public static Map<String, Object> map() {
        return new TreeMap<>();
    }

    private static void append(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> appendString(sb, s);
            case Boolean b -> sb.append(b ? "true" : "false");
            case Integer i -> sb.append(i.intValue());
            case Long l -> sb.append(l.longValue());
            case Map<?, ?> m -> appendMap(sb, m);
            case List<?> l -> appendList(sb, l);
            default -> throw new IllegalArgumentException(
                    "정규형 JSON 이 다루지 않는 타입: " + value.getClass().getName()
                            + ". 실수로 흘러든 값이면 매퍼에서 잡아야 한다.");
        }
    }

    private static void appendMap(StringBuilder sb, Map<?, ?> m) {
        Map<String, Object> sorted = (m instanceof TreeMap) ? castMap(m) : sortedCopy(m);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendString(sb, e.getKey());
            sb.append(':');
            append(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendList(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            append(sb, list.get(i));
        }
        sb.append(']');
    }

    /** Python 의 이스케이프 규칙과 같다. 비ASCII 는 UTF-8 그대로 내보낸다. */
    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Map<String, Object> sortedCopy(Map<?, ?> m) {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
