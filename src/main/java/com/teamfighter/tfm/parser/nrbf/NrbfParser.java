package com.teamfighter.tfm.parser.nrbf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NRBF 스트림 하나를 객체 그래프로 읽는다.
 *
 * <p>레퍼런스 구현은 {@code tools/nrbf.py} 의 {@code Parser} 다.
 * 결과는 {@code tests/baseline/*.json} 골든 파일과 일치해야 한다.
 *
 * <p>참조는 2패스로 푼다. {@code MemberReference} 가 아직 읽지 않은 id 를 가리킬 수 있어서
 * 읽는 중에 해소하려 하면 null 이 박힌다.
 */
public final class NrbfParser {

    /** 스트림 시작 표식. 세이브 파일은 이 헤더로 시작하는 스트림 3개가 이어붙어 있다. */
    public static final byte[] STREAM_HEADER = {
            0x00, 0x01, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    private final NrbfReader r;
    private final Map<Integer, Object> objects = new HashMap<>();
    private final Map<Integer, ClassMeta> classes = new HashMap<>();
    private int rootId;
    private boolean done;

    public NrbfParser(byte[] buf, int start, int end) {
        this.r = new NrbfReader(buf, start, end);
    }

    // ------------------------------------------------------------------ 진입점

    public NrbfParser parse() {
        while (!done && !r.eof()) {
            readRecord();
        }
        resolve();
        return this;
    }

    public Map<Integer, Object> objects() {
        return objects;
    }

    public Object root() {
        return objects.get(rootId);
    }

    public int rootId() {
        return rootId;
    }

    /** 이어붙은 스트림들의 경계 {@code [start, end)}. */
    public static List<int[]> splitStreams(byte[] buf) {
        List<int[]> offsets = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + STREAM_HEADER.length <= buf.length; i++) {
            if (matchesHeader(buf, i)) {
                starts.add(i);
            }
        }
        for (int n = 0; n < starts.size(); n++) {
            int end = (n + 1 < starts.size()) ? starts.get(n + 1) : buf.length;
            offsets.add(new int[]{starts.get(n), end});
        }
        return offsets;
    }

    private static boolean matchesHeader(byte[] buf, int at) {
        for (int k = 0; k < STREAM_HEADER.length; k++) {
            if (buf[at + k] != STREAM_HEADER[k]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 레코드

    private void readRecord() {
        int type = r.readByte();
        switch (type) {
            case RecordType.SERIALIZED_STREAM_HEADER -> {
                rootId = r.readInt32();
                r.skip(12);                       // HeaderId, MajorVersion, MinorVersion
            }
            case RecordType.MESSAGE_END -> done = true;
            case RecordType.BINARY_LIBRARY -> {
                r.readInt32();
                r.readString();
            }
            default -> readValueRecord(type);
        }
    }

    /** 값을 나타내는 레코드를 읽고 그 값을 돌려준다. */
    private Object readValueRecord(int type) {
        switch (type) {
            case RecordType.BINARY_OBJECT_STRING -> {
                int id = r.readInt32();
                return store(id, r.readString());
            }
            case RecordType.CLASS_WITH_MEMBERS_AND_TYPES,
                 RecordType.SYSTEM_CLASS_WITH_MEMBERS_AND_TYPES,
                 RecordType.CLASS_WITH_MEMBERS,
                 RecordType.SYSTEM_CLASS_WITH_MEMBERS -> {
                return readClass(type);
            }
            case RecordType.CLASS_WITH_ID -> {
                int id = r.readInt32();
                ClassMeta meta = classes.get(r.readInt32());
                if (meta == null) {
                    throw new NrbfException("ClassWithId 가 모르는 메타데이터를 가리킨다 (pos=" + r.position() + ")");
                }
                return fill(id, meta);
            }
            case RecordType.ARRAY_SINGLE_STRING, RecordType.ARRAY_SINGLE_OBJECT -> {
                int id = r.readInt32();
                int n = r.readInt32();
                return store(id, readItems(n));
            }
            case RecordType.ARRAY_SINGLE_PRIMITIVE -> {
                int id = r.readInt32();
                int n = r.readInt32();
                int ptype = r.readByte();
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    items.add(PrimitiveType.read(r, ptype));
                }
                return store(id, items);
            }
            case RecordType.BINARY_ARRAY -> {
                return readBinaryArray();
            }
            case RecordType.MEMBER_REFERENCE -> {
                return new NrbfRef(r.readInt32());
            }
            case RecordType.OBJECT_NULL -> {
                return null;
            }
            case RecordType.MEMBER_PRIMITIVE_TYPED -> {
                return PrimitiveType.read(r, r.readByte());
            }
            default -> throw new NrbfException("알 수 없는 레코드 타입 " + type + " (pos=" + r.position() + ")");
        }
    }

    // ------------------------------------------------------------------ 클래스

    private Object readClass(int type) {
        int id = r.readInt32();
        String name = r.readString();
        int count = r.readInt32();

        List<String> memberNames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            memberNames.add(r.readString());
        }

        boolean hasTypes = type == RecordType.CLASS_WITH_MEMBERS_AND_TYPES
                || type == RecordType.SYSTEM_CLASS_WITH_MEMBERS_AND_TYPES;
        List<ClassMeta.MemberType> memberTypes = hasTypes
                ? readMemberTypes(count)
                : Collections.nCopies(count, new ClassMeta.MemberType(BinaryType.OBJECT, null));

        if (type == RecordType.CLASS_WITH_MEMBERS_AND_TYPES || type == RecordType.CLASS_WITH_MEMBERS) {
            r.readInt32();                        // LibraryId
        }

        ClassMeta meta = new ClassMeta(name, memberNames, memberTypes);
        classes.put(id, meta);
        return fill(id, meta);
    }

    private List<ClassMeta.MemberType> readMemberTypes(int count) {
        int[] kinds = new int[count];
        for (int i = 0; i < count; i++) {
            kinds[i] = r.readByte();
        }
        List<ClassMeta.MemberType> out = new ArrayList<>(count);
        for (int kind : kinds) {
            Object extra = null;
            switch (kind) {
                case BinaryType.PRIMITIVE, BinaryType.PRIMITIVE_ARRAY -> extra = r.readByte();
                case BinaryType.SYSTEM_CLASS -> extra = r.readString();
                case BinaryType.CLASS -> {
                    extra = r.readString();
                    r.readInt32();                // LibraryId
                }
                default -> {
                }
            }
            out.add(new ClassMeta.MemberType(kind, extra));
        }
        return out;
    }

    /**
     * 인스턴스를 만들고 멤버를 채운다.
     *
     * <p>멤버를 읽기 <b>전에</b> 먼저 등록한다. 자기 자신을 참조하는 그래프에서
     * 나중에 등록하면 그 참조가 풀리지 않는다.
     */
    private Object fill(int id, ClassMeta meta) {
        NrbfObject obj = new NrbfObject(meta.name());
        store(id, obj);
        for (int i = 0; i < meta.memberNames().size(); i++) {
            ClassMeta.MemberType mt = meta.memberTypes().get(i);
            obj.members().put(meta.memberNames().get(i), readMember(mt));
        }
        return obj;
    }

    private Object readMember(ClassMeta.MemberType mt) {
        if (mt.binaryType() == BinaryType.PRIMITIVE) {
            return PrimitiveType.read(r, (Integer) mt.extra());
        }
        return readValueRecord(r.readByte());
    }

    // ------------------------------------------------------------------ 배열

    private Object readBinaryArray() {
        int id = r.readInt32();
        int arrayType = r.readByte();
        int rank = r.readInt32();

        int total = 1;
        for (int i = 0; i < rank; i++) {
            total *= r.readInt32();
        }
        if (arrayType == 3 || arrayType == 4 || arrayType == 5) {   // *Offset 계열은 하한이 따로 온다
            for (int i = 0; i < rank; i++) {
                r.readInt32();
            }
        }

        int btype = r.readByte();
        Object extra = null;
        switch (btype) {
            case BinaryType.PRIMITIVE, BinaryType.PRIMITIVE_ARRAY -> extra = r.readByte();
            case BinaryType.SYSTEM_CLASS -> extra = r.readString();
            case BinaryType.CLASS -> {
                extra = r.readString();
                r.readInt32();
            }
            default -> {
            }
        }

        if (btype == BinaryType.PRIMITIVE) {
            List<Object> items = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                items.add(PrimitiveType.read(r, (Integer) extra));
            }
            return store(id, items);
        }
        return store(id, readItems(total));
    }

    /**
     * 배열 원소를 읽는다.
     *
     * <p>{@code ObjectNullMultiple} 이 null 여러 개를 레코드 하나로 표현한다.
     * 원소 하나에 레코드 하나를 가정하면 어긋난다 — .NET {@code List<T>} 의
     * 용량 여유분이 전부 이 형태로 저장되기 때문에 실제로 자주 나온다.
     */
    private List<Object> readItems(int count) {
        List<Object> items = new ArrayList<>(count);
        while (items.size() < count) {
            int type = r.readByte();
            switch (type) {
                case RecordType.OBJECT_NULL -> items.add(null);
                case RecordType.OBJECT_NULL_MULTIPLE_256 -> addNulls(items, r.readByte());
                case RecordType.OBJECT_NULL_MULTIPLE -> addNulls(items, r.readInt32());
                default -> items.add(readValueRecord(type));
            }
        }
        return items;
    }

    private static void addNulls(List<Object> items, int n) {
        for (int i = 0; i < n; i++) {
            items.add(null);
        }
    }

    // ------------------------------------------------------------------ 저장·해소

    private Object store(int id, Object value) {
        if (id != 0) {
            objects.put(id, value);
        }
        return value;
    }

    /** {@link NrbfRef} 를 실제 객체로 바꾼다. 파싱이 끝난 뒤에만 안전하다. */
    @SuppressWarnings("unchecked")
    private void resolve() {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        // ArrayDeque 는 null 원소를 허용하지 않는다. 시작 집합에서 걸러 넣는다.
        Deque<Object> stack = new ArrayDeque<>();
        for (Object v : objects.values()) {
            if (v != null) {
                stack.push(v);
            }
        }

        while (!stack.isEmpty()) {
            Object node = stack.pop();
            if (node == null || !seen.add(node)) {
                continue;
            }
            if (node instanceof NrbfObject obj) {
                for (Map.Entry<String, Object> e : obj.members().entrySet()) {
                    if (e.getValue() instanceof NrbfRef ref) {
                        e.setValue(objects.get(ref.id()));
                    }
                    if (e.getValue() != null) {
                        stack.push(e.getValue());
                    }
                }
            } else if (node instanceof List<?> list) {
                List<Object> items = (List<Object>) list;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i) instanceof NrbfRef ref) {
                        items.set(i, objects.get(ref.id()));
                    }
                    if (items.get(i) != null) {
                        stack.push(items.get(i));
                    }
                }
            }
        }
    }
}
