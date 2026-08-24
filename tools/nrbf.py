"""MS-NRBF (.NET BinaryFormatter) 파서 — 레퍼런스 구현.

Java 포팅의 원본이다. Java 결과가 이 구현의 출력과 일치해야 한다 (project.md 제약).

세이브 파일은 NRBF 스트림 3개가 이어붙어 있다 (savefile.md).
암호화도 압축도 없고 타입명·필드명이 파일 안에 그대로 들어 있다.

사용:
    python tools/nrbf.py fixtures/slot_638683925954242004.tfm
    python tools/nrbf.py fixtures/slot_638683925954242004.tfm --classes
"""
from __future__ import annotations

import struct
import sys
from dataclasses import dataclass, field

# 스트림 시작을 알리는 SerializedStreamHeader 17바이트.
# 00 = 레코드 타입 0, RootId=1, HeaderId=-1, Major=1, Minor=0
STREAM_HEADER = bytes([0x00, 0x01, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF,
                       0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])


class RecordType:
    SERIALIZED_STREAM_HEADER = 0
    CLASS_WITH_ID = 1
    SYSTEM_CLASS_WITH_MEMBERS = 2
    CLASS_WITH_MEMBERS = 3
    SYSTEM_CLASS_WITH_MEMBERS_AND_TYPES = 4
    CLASS_WITH_MEMBERS_AND_TYPES = 5
    BINARY_OBJECT_STRING = 6
    BINARY_ARRAY = 7
    MEMBER_PRIMITIVE_TYPED = 8
    MEMBER_REFERENCE = 9
    OBJECT_NULL = 10
    MESSAGE_END = 11
    BINARY_LIBRARY = 12
    OBJECT_NULL_MULTIPLE_256 = 13
    OBJECT_NULL_MULTIPLE = 14
    ARRAY_SINGLE_PRIMITIVE = 15
    ARRAY_SINGLE_OBJECT = 16
    ARRAY_SINGLE_STRING = 17


class BinaryType:
    PRIMITIVE = 0
    STRING = 1
    OBJECT = 2
    SYSTEM_CLASS = 3
    CLASS = 4
    OBJECT_ARRAY = 5
    STRING_ARRAY = 6
    PRIMITIVE_ARRAY = 7


# PrimitiveTypeEnum -> (struct 포맷, 바이트 수). None 은 특수 처리.
_PRIMITIVES = {
    1:  ("?", 1),   # Boolean
    2:  ("B", 1),   # Byte      부호 없음. Java 포팅에서 & 0xFF 가 필요한 지점
    3:  (None, 0),  # Char      UTF-8 가변 길이
    5:  (None, 0),  # Decimal   길이 접두 문자열
    6:  ("<d", 8),  # Double
    7:  ("<h", 2),  # Int16
    8:  ("<i", 4),  # Int32
    9:  ("<q", 8),  # Int64
    10: ("b", 1),   # SByte
    11: ("<f", 4),  # Single
    12: ("<q", 8),  # TimeSpan
    13: ("<q", 8),  # DateTime  (틱 + 종류 비트. 원시값 그대로 둔다)
    14: ("<H", 2),  # UInt16
    15: ("<I", 4),  # UInt32
    16: ("<Q", 8),  # UInt64
    18: (None, 0),  # String
}


@dataclass
class NrbfObject:
    """클래스 인스턴스 하나. 필드명이 파일에 들어 있어서 그대로 쓴다."""
    class_name: str
    members: dict = field(default_factory=dict)

    def __repr__(self) -> str:
        return "<%s %s>" % (self.class_name, list(self.members)[:4])


@dataclass
class Ref:
    """MemberReference. 2패스로 해소한다 — 아직 안 읽은 id 도 참조하기 때문."""
    id: int


@dataclass
class ClassMeta:
    """ClassWithId 가 재사용하는 클래스 정의."""
    name: str
    member_names: list
    member_types: list   # (BinaryType, extra) 목록


class Reader:
    """리틀엔디언 바이트 리더.

    Java 포팅 주의: Java 의 byte 는 부호가 있다. 여기서 부호 없이 읽는 곳
    (Byte, 7-bit 길이 접두, 레코드 타입)은 전부 & 0xFF 가 필요하다.
    """

    def __init__(self, buf, pos=0, end=None):
        self.buf = buf
        self.pos = pos
        self.end = len(buf) if end is None else end

    def eof(self):
        return self.pos >= self.end

    def byte(self):
        v = self.buf[self.pos]
        self.pos += 1
        return v

    def take(self, n):
        v = self.buf[self.pos:self.pos + n]
        if len(v) != n:
            raise EOFError("%d바이트를 읽으려 했으나 %d바이트만 남음 (pos=%d)" % (n, len(v), self.pos))
        self.pos += n
        return v

    def unpack(self, fmt, size):
        return struct.unpack(fmt, self.take(size))[0]

    def int32(self):
        return self.unpack("<i", 4)

    def length(self):
        """7-bit encoded int. 하위 7비트씩, 최상위 비트가 '계속됨'을 뜻한다."""
        value = 0
        shift = 0
        for _ in range(5):
            b = self.byte()
            value |= (b & 0x7F) << shift
            if not (b & 0x80):
                return value
            shift += 7
        raise ValueError("7-bit encoded int 가 5바이트를 넘었다")

    def string(self):
        return self.take(self.length()).decode("utf-8")

    def primitive(self, ptype):
        if ptype == 18 or ptype == 5:            # String, Decimal
            return self.string()
        if ptype == 3:                            # Char (UTF-8 가변 길이)
            first = self.buf[self.pos]
            n = 1 if first < 0x80 else 2 if first < 0xE0 else 3 if first < 0xF0 else 4
            return self.take(n).decode("utf-8")
        fmt, size = _PRIMITIVES[ptype]
        return self.unpack(fmt, size) if fmt else None


class Parser:
    """NRBF 스트림 하나를 객체 그래프로 읽는다."""

    def __init__(self, buf, start, end):
        self.r = Reader(buf, start, end)
        self.objects = {}
        self.classes = {}
        self.root_id = 0
        self._done = False

    # ---------------------------------------------------------------- 진입점
    def parse(self):
        while not self._done and not self.r.eof():
            self._read_record()
        self._resolve()
        return self

    def root(self):
        return self.objects.get(self.root_id)

    # ---------------------------------------------------------------- 레코드
    def _read_record(self):
        rt = self.r.byte()
        if rt == RecordType.SERIALIZED_STREAM_HEADER:
            self.root_id = self.r.int32()
            self.r.take(12)                       # HeaderId, Major, Minor
        elif rt == RecordType.MESSAGE_END:
            self._done = True
        elif rt == RecordType.BINARY_LIBRARY:
            self.r.int32()
            self.r.string()
        else:
            self._read_value_record(rt)

    def _read_value_record(self, rt):
        """값을 나타내는 레코드를 읽고 그 값을 반환한다."""
        if rt == RecordType.BINARY_OBJECT_STRING:
            oid = self.r.int32()
            return self._store(oid, self.r.string())

        if rt in (RecordType.CLASS_WITH_MEMBERS_AND_TYPES,
                  RecordType.SYSTEM_CLASS_WITH_MEMBERS_AND_TYPES,
                  RecordType.CLASS_WITH_MEMBERS,
                  RecordType.SYSTEM_CLASS_WITH_MEMBERS):
            return self._read_class(rt)

        if rt == RecordType.CLASS_WITH_ID:
            oid = self.r.int32()
            meta = self.classes[self.r.int32()]
            return self._fill(oid, meta)

        if rt == RecordType.ARRAY_SINGLE_STRING:
            oid, n = self.r.int32(), self.r.int32()
            return self._store(oid, self._read_items(n))

        if rt == RecordType.ARRAY_SINGLE_OBJECT:
            oid, n = self.r.int32(), self.r.int32()
            return self._store(oid, self._read_items(n))

        if rt == RecordType.ARRAY_SINGLE_PRIMITIVE:
            oid, n = self.r.int32(), self.r.int32()
            ptype = self.r.byte()
            return self._store(oid, [self.r.primitive(ptype) for _ in range(n)])

        if rt == RecordType.BINARY_ARRAY:
            return self._read_binary_array()

        if rt == RecordType.MEMBER_REFERENCE:
            return Ref(self.r.int32())

        if rt == RecordType.OBJECT_NULL:
            return None

        if rt == RecordType.MEMBER_PRIMITIVE_TYPED:
            return self.r.primitive(self.r.byte())

        raise ValueError("알 수 없는 레코드 타입 %d (pos=%d)" % (rt, self.r.pos))

    # ---------------------------------------------------------------- 클래스
    def _read_class(self, rt):
        oid = self.r.int32()
        name = self.r.string()
        count = self.r.int32()
        member_names = [self.r.string() for _ in range(count)]

        has_types = rt in (RecordType.CLASS_WITH_MEMBERS_AND_TYPES,
                           RecordType.SYSTEM_CLASS_WITH_MEMBERS_AND_TYPES)
        if has_types:
            member_types = self._read_member_types(count)
        else:
            member_types = [(BinaryType.OBJECT, None)] * count

        if rt in (RecordType.CLASS_WITH_MEMBERS_AND_TYPES, RecordType.CLASS_WITH_MEMBERS):
            self.r.int32()                        # LibraryId

        meta = ClassMeta(name, member_names, member_types)
        self.classes[oid] = meta
        return self._fill(oid, meta)

    def _read_member_types(self, count):
        kinds = [self.r.byte() for _ in range(count)]
        out = []
        for k in kinds:
            if k in (BinaryType.PRIMITIVE, BinaryType.PRIMITIVE_ARRAY):
                out.append((k, self.r.byte()))
            elif k == BinaryType.SYSTEM_CLASS:
                out.append((k, self.r.string()))
            elif k == BinaryType.CLASS:
                cname = self.r.string()
                self.r.int32()                    # LibraryId
                out.append((k, cname))
            else:
                out.append((k, None))
        return out

    def _fill(self, oid, meta):
        obj = NrbfObject(meta.name)
        self._store(oid, obj)
        for name, (btype, extra) in zip(meta.member_names, meta.member_types):
            obj.members[name] = self._read_member(btype, extra)
        return obj

    def _read_member(self, btype, extra):
        if btype == BinaryType.PRIMITIVE:
            return self.r.primitive(extra)
        return self._read_value_record(self.r.byte())

    # ---------------------------------------------------------------- 배열
    def _read_binary_array(self):
        oid = self.r.int32()
        array_type = self.r.byte()
        rank = self.r.int32()
        lengths = [self.r.int32() for _ in range(rank)]
        if array_type in (3, 4, 5):               # *Offset 계열은 하한이 따로 온다
            [self.r.int32() for _ in range(rank)]

        btype = self.r.byte()
        extra = None
        if btype in (BinaryType.PRIMITIVE, BinaryType.PRIMITIVE_ARRAY):
            extra = self.r.byte()
        elif btype == BinaryType.SYSTEM_CLASS:
            extra = self.r.string()
        elif btype == BinaryType.CLASS:
            extra = self.r.string()
            self.r.int32()

        total = 1
        for n in lengths:
            total *= n

        if btype == BinaryType.PRIMITIVE:
            items = [self.r.primitive(extra) for _ in range(total)]
        else:
            items = self._read_items(total)
        return self._store(oid, items)

    def _read_items(self, count):
        """배열 원소를 읽는다. ObjectNullMultiple 이 null 여러 개를 한 레코드로 표현한다."""
        items = []
        while len(items) < count:
            rt = self.r.byte()
            if rt == RecordType.OBJECT_NULL:
                items.append(None)
            elif rt == RecordType.OBJECT_NULL_MULTIPLE_256:
                items.extend([None] * self.r.byte())
            elif rt == RecordType.OBJECT_NULL_MULTIPLE:
                items.extend([None] * self.r.int32())
            else:
                items.append(self._read_value_record(rt))
        return items

    # ---------------------------------------------------------------- 저장·해소
    def _store(self, oid, value):
        if oid:
            self.objects[oid] = value
        return value

    def _resolve(self):
        """Ref 를 실제 객체로 바꾼다. 아직 안 읽은 id 도 참조하므로 파싱이 끝난 뒤에 한다."""
        seen = set()
        stack = list(self.objects.values())
        while stack:
            node = stack.pop()
            if id(node) in seen:
                continue
            seen.add(id(node))
            if isinstance(node, NrbfObject):
                for k, v in node.members.items():
                    if isinstance(v, Ref):
                        node.members[k] = self.objects.get(v.id)
                    stack.append(node.members[k])
            elif isinstance(node, list):
                for i, v in enumerate(node):
                    if isinstance(v, Ref):
                        node[i] = self.objects.get(v.id)
                    stack.append(node[i])


def split_streams(buf):
    """이어붙은 NRBF 스트림들의 (start, end) 경계."""
    offsets = []
    i = buf.find(STREAM_HEADER)
    while i != -1:
        offsets.append(i)
        i = buf.find(STREAM_HEADER, i + 1)
    bounds = offsets + [len(buf)]
    return [(bounds[n], bounds[n + 1]) for n in range(len(offsets))]


def parse_file(path, streams=None):
    """세이브 파일을 파싱한다. streams 로 특정 스트림만 고를 수 있다.

    세이브 파일은 읽기만 한다. 어떤 경우에도 쓰지 않는다 (project.md 제약).
    """
    with open(path, "rb") as f:
        buf = f.read()
    out = []
    for n, (start, end) in enumerate(split_streams(buf)):
        if streams is not None and n not in streams:
            out.append(None)
            continue
        out.append(Parser(buf, start, end).parse())
    return out


def class_counts(parser):
    counts = {}
    for v in parser.objects.values():
        if isinstance(v, NrbfObject):
            counts[v.class_name] = counts.get(v.class_name, 0) + 1
    return counts


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    path = argv[0]
    show_classes = "--classes" in argv

    with open(path, "rb") as f:
        buf = f.read()
    bounds = split_streams(buf)
    print("%s  %s bytes  스트림 %d개" % (path, format(len(buf), ","), len(bounds)))

    for n, (start, end) in enumerate(bounds):
        parser = Parser(buf, start, end).parse()
        print("  스트림 %d: %10s bytes  객체 %7s  root=%d"
              % (n, format(end - start, ","), format(len(parser.objects), ","), parser.root_id))
        if show_classes:
            for name, c in sorted(class_counts(parser).items(), key=lambda kv: -kv[1])[:15]:
                print("      %7s  %s" % (format(c, ","), name))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
