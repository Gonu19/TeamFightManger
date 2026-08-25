package com.teamfighter.tfm.parser.save;

import com.teamfighter.tfm.parser.nrbf.NrbfObject;

import java.util.ArrayList;
import java.util.List;

/**
 * NRBF 객체 그래프에서 값을 꺼낼 때의 .NET 관례를 한곳에 모은다.
 *
 * <p>레퍼런스 구현은 {@code tools/save_model.py} 다.
 */
public final class SaveValues {

    private SaveValues() {
    }

    /**
     * 멤버를 읽는다. 자동 프로퍼티의 백킹 필드({@code <Name>k__BackingField})까지 찾아본다.
     *
     * <p>enum 은 정수로 풀어서 돌려준다 — 아래 {@link #unwrapEnum} 참고.
     */
    public static Object get(Object owner, String name) {
        if (!(owner instanceof NrbfObject obj)) {
            return null;
        }
        Object v = obj.has(name) ? obj.get(name) : obj.get("<" + name + ">k__BackingField");
        return unwrapEnum(v);
    }

    /**
     * .NET enum 은 {@code value__} 하나짜리 객체로 직렬화된다. 정수로 되돌린다.
     *
     * <p>{@code TeamType}(0=BLUE, 1=RED), {@code ChampionCategory} 등이 전부 이 모양이다.
     * 풀지 않으면 값 비교도 직렬화도 되지 않는다.
     */
    public static Object unwrapEnum(Object v) {
        if (v instanceof NrbfObject obj
                && obj.members().size() == 1
                && obj.has("value__")) {
            return obj.get("value__");
        }
        return v;
    }

    /**
     * .NET {@code List<T>} 를 자바 리스트로.
     *
     * <p><b>{@code _items} 의 길이로 세면 틀린다.</b> 용량 여유분이 뒤에 {@code null} 로 달려 있어
     * {@code _size} 만큼만 유효하다. 밴이 2개인 경기가 {@code ['A','B',null,null]} 로 보이는 이유다.
     */
    public static List<Object> list(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> raw) {
            return new ArrayList<>(raw);
        }
        if (!(v instanceof NrbfObject obj)) {
            return List.of();
        }
        Object items = obj.get("_items");
        if (!(items instanceof List<?> raw)) {
            return List.of();
        }
        Object size = obj.get("_size");
        if (size instanceof Integer n) {
            return new ArrayList<>(raw.subList(0, Math.min(n, raw.size())));
        }
        List<Object> out = new ArrayList<>();
        for (Object o : raw) {
            if (o != null) {
                out.add(o);
            }
        }
        return out;
    }

    /** {@code List<string>} → 문자열 목록. null 은 버린다(밴이 2개인 경기). */
    public static List<String> names(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) {
            if (o instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 정수 멤버를 읽는다.
     *
     * <p><b>다른 수치 타입을 잘라내지 않는다.</b> 아무 {@code Number} 나 받아
     * {@code intValue()} 로 줄이면, 게임이 필드 타입을 바꿨을 때(Int32 → Int64/Single)
     * 소수부를 버리거나 하위 32비트만 남긴 값이 조용히 통계로 들어간다.
     * 레퍼런스 구현은 파싱된 값을 그대로 내보내므로 그 순간 결과가 갈린다.
     */
    public static Integer intOf(Object owner, String name) {
        Object v = get(owner, name);
        if (v == null) {
            return null;
        }
        if (v instanceof Integer i) {
            return i;
        }
        throw new IllegalStateException(
                name + " 이 Int32 가 아니다: " + v.getClass().getSimpleName() + " = " + v
                        + ". 세이브 파일의 필드 타입이 바뀌었을 수 있다");
    }

    public static Boolean boolOf(Object owner, String name) {
        Object v = get(owner, name);
        return (v instanceof Boolean b) ? b : null;
    }

    public static String stringOf(Object owner, String name) {
        Object v = get(owner, name);
        return (v instanceof String s) ? s : null;
    }
}
