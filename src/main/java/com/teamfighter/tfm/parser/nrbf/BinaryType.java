package com.teamfighter.tfm.parser.nrbf;

/** MS-NRBF BinaryTypeEnum. 멤버 하나를 어떻게 읽을지 결정한다. */
public final class BinaryType {

    public static final int PRIMITIVE = 0;
    public static final int STRING = 1;
    public static final int OBJECT = 2;
    public static final int SYSTEM_CLASS = 3;
    public static final int CLASS = 4;
    public static final int OBJECT_ARRAY = 5;
    public static final int STRING_ARRAY = 6;
    public static final int PRIMITIVE_ARRAY = 7;

    private BinaryType() {
    }
}
