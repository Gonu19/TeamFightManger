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
            case SBYTE -> (int) r.readSByte();
            case SINGLE -> r.readSingle();
            case UINT16 -> r.readUInt16();
            case UINT32 -> r.readUInt32();
            case UINT64 -> r.readInt64();
            default -> throw new NrbfException("알 수 없는 PrimitiveType " + type + " (pos=" + r.position() + ")");
        };
    }
}
