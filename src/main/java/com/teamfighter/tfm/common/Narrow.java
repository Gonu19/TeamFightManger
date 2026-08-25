package com.teamfighter.tfm.common;

/**
 * 폭이 좁은 타입으로 줄일 때 범위를 확인한다.
 *
 * <p>Java 의 캐스팅은 넘치는 값을 <b>조용히 감아버린다.</b> 65537 을 short 로 캐스팅하면 1 이 된다.
 * 이 값이 자연키의 일부라면(pick_order, ban_order) 서로 다른 두 행이 같은 키로 충돌한다.
 * 잘못된 값으로 계속 가느니 여기서 멈춘다.
 */
public final class Narrow {

    private Narrow() {
    }

    public static short toShort(int value, String field) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + " 가 short 범위를 벗어난다: " + value + ". 캐스팅하면 조용히 감긴다");
        }
        return (short) value;
    }

    public static Short toShort(Integer value, String field) {
        return value == null ? null : toShort(value.intValue(), field);
    }
}
