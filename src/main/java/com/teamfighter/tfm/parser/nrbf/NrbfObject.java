package com.teamfighter.tfm.parser.nrbf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 클래스 인스턴스 하나.
 *
 * <p>NRBF 는 타입명과 필드명을 파일에 그대로 담는다. 그래서 스키마를 미리 알 필요 없이
 * 이름으로 꺼내 쓸 수 있다. 멤버 순서는 파일에 적힌 순서를 유지한다.
 */
public final class NrbfObject {

    private final String className;
    private final Map<String, Object> members = new LinkedHashMap<>();

    public NrbfObject(String className) {
        this.className = className;
    }

    public String className() {
        return className;
    }

    public Map<String, Object> members() {
        return members;
    }

    public Object get(String name) {
        return members.get(name);
    }

    public boolean has(String name) {
        return members.containsKey(name);
    }

    @Override
    public String toString() {
        return "<" + className + " " + members.keySet() + ">";
    }
}
