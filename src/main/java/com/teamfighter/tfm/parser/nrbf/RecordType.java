package com.teamfighter.tfm.parser.nrbf;

/** MS-NRBF RecordTypeEnum. 스트림의 모든 레코드가 이 값 하나로 시작한다. */
public final class RecordType {

    public static final int SERIALIZED_STREAM_HEADER = 0;
    public static final int CLASS_WITH_ID = 1;
    public static final int SYSTEM_CLASS_WITH_MEMBERS = 2;
    public static final int CLASS_WITH_MEMBERS = 3;
    public static final int SYSTEM_CLASS_WITH_MEMBERS_AND_TYPES = 4;
    public static final int CLASS_WITH_MEMBERS_AND_TYPES = 5;
    public static final int BINARY_OBJECT_STRING = 6;
    public static final int BINARY_ARRAY = 7;
    public static final int MEMBER_PRIMITIVE_TYPED = 8;
    public static final int MEMBER_REFERENCE = 9;
    public static final int OBJECT_NULL = 10;
    public static final int MESSAGE_END = 11;
    public static final int BINARY_LIBRARY = 12;
    public static final int OBJECT_NULL_MULTIPLE_256 = 13;
    public static final int OBJECT_NULL_MULTIPLE = 14;
    public static final int ARRAY_SINGLE_PRIMITIVE = 15;
    public static final int ARRAY_SINGLE_OBJECT = 16;
    public static final int ARRAY_SINGLE_STRING = 17;

    private RecordType() {
    }
}
