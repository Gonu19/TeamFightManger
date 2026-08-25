package com.teamfighter.tfm.parser.nrbf;

/** MS-NRBF PrimitiveTypeEnum. 값 하나를 읽는다. */
public final class PrimitiveType {

    public static final int BOOLEAN = 1;
    public static final int BYTE = 2;
    public static final int CHAR = 3;
    public static final int DECIMAL = 5;
    public static final int DOUBLE = 6;
    public static final int INT16 = 7;
    public static final int INT32 = 8;
    public static final int INT64 = 9;
    public static final int SBYTE = 10;
    public static final int SINGLE = 11;
    public static final int TIME_SPAN = 12;
    public static final int DATE_TIME = 13;
    public static final int UINT16 = 14;
    public static final int UINT32 = 15;
    public static final int UINT64 = 16;
    public static final int STRING = 18;

    private PrimitiveType() {
    }

    /**
     * 값 하나를 읽는다.
     *
     * <p>DateTime / TimeSpan 은 틱과 종류 비트가 섞인 64비트다.
     * 해석하지 않고 원시값 그대로 둔다 — 이 프로젝트가 쓰는 시점 정보는
     * {@code TodayData} 와 {@code SeasonTime} 에서 오지 여기서 오지 않는다.
     */
    /**
     * UInt64.
     *
     * <p>Java 에는 unsigned long 이 없다. 상위 비트가 켜진 값은 음수 long 이 되어
     * 레퍼런스 구현(Python 은 임의 정밀도)과 다른 숫자를 내놓는다.
     * 조용히 틀린 값을 흘려보내느니 여기서 멈춘다 — 실측한 세이브 3개에는 UInt64 가
     * 한 번도 나오지 않으므로, 이 예외가 뜬다면 그 자체가 새로운 정보다.
     */
    private static long readUInt64(NrbfReader r) {
        long v = r.readInt64();
        if (v < 0) {
            throw new NrbfException(
                    "UInt64 값이 long 으로 표현되지 않는다 (raw=" + Long.toUnsignedString(v)
                            + ", pos=" + r.position() + ")");
        }
        return v;
    }

    public static Object read(NrbfReader r, int type) {
        return switch (type) {
            case BOOLEAN -> r.readBoolean();
            case BYTE -> r.readByte();
            case CHAR -> r.readChar();
            case DECIMAL, STRING -> r.readString();
            case DOUBLE -> r.readDouble();
            case INT16 -> (int) r.readInt16();
            case INT32 -> r.readInt32();
            case INT64, TIME_SPAN, DATE_TIME -> r.readInt64();
            case UINT64 -> readUInt64(r);
            case SBYTE -> (int) r.readSByte();
            case SINGLE -> r.readSingle();
            case UINT16 -> r.readUInt16();
            case UINT32 -> r.readUInt32();
            default -> throw new NrbfException("알 수 없는 PrimitiveType " + type + " (pos=" + r.position() + ")");
        };
    }
}
