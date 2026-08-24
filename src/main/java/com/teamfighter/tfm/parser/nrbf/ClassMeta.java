package com.teamfighter.tfm.parser.nrbf;

import java.util.List;

/**
 * 클래스 정의. {@code ClassWithId} 가 같은 정의를 재사용한다 —
 * 같은 타입이 수천 번 나와도 필드명은 한 번만 기록된다.
 *
 * @param memberTypes 멤버별 (BinaryType, 부가정보) 쌍
 */
public record ClassMeta(String name, List<String> memberNames, List<MemberType> memberTypes) {

    /** @param extra PRIMITIVE/PRIMITIVE_ARRAY 면 PrimitiveType, SYSTEM_CLASS/CLASS 면 타입명, 그 외 null */
    public record MemberType(int binaryType, Object extra) {
    }
}
