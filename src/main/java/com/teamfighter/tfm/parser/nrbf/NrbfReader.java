package com.teamfighter.tfm.parser.nrbf;

import java.nio.charset.StandardCharsets;

/**
 * NRBF 바이트 리더. 리틀엔디언.
 *
 * <p><b>Java 의 {@code byte} 는 부호가 있고 NRBF 는 unsigned 를 다룬다.</b>
 * 부호 없이 읽어야 하는 곳에서 {@code & 0xFF} 를 빼먹으면 예외 없이 조용히 틀린 값이 나온다.
 * 이 클래스가 그 변환을 전담한다 — 바깥에서 {@code buf[i]} 를 직접 만지지 않는다.
 *
 * <p>레퍼런스 구현은 {@code tools/nrbf.py} 의 {@code Reader} 다.
 */
public final class NrbfReader {

    private final byte[] buf;
    private final int end;
    private int pos;

    public NrbfReader(byte[] buf, int start, int end) {
        this.buf = buf;
        this.pos = start;
        this.end = end;
    }

    public int position() {
        return pos;
    }

    public boolean eof() {
        return pos >= end;
    }

    /** 부호 없는 1바이트. 여기가 {@code & 0xFF} 가 반드시 필요한 지점이다. */
    public int readByte() {
        require(1);
        return buf[pos++] & 0xFF;
    }

    /** 부호 있는 1바이트 (SByte). */
    public byte readSByte() {
        require(1);
        return buf[pos++];
    }

    public byte[] readBytes(int n) {
        require(n);
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    public void skip(int n) {
        require(n);
        pos += n;
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public short readInt16() {
        require(2);
        int v = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8);
        pos += 2;
        return (short) v;
    }

    public int readUInt16() {
        return readInt16() & 0xFFFF;
    }

    public int readInt32() {
        require(4);
        int v = (buf[pos] & 0xFF)
                | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16)
                | ((buf[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    /** UInt32. Java 에 unsigned int 가 없어서 long 으로 넓힌다. */
    public long readUInt32() {
        return readInt32() & 0xFFFFFFFFL;
    }

    public long readInt64() {
        require(8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (buf[pos + i] & 0xFF);
        }
        pos += 8;
        return v;
    }

    public float readSingle() {
        return Float.intBitsToFloat(readInt32());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readInt64());
    }

    /**
     * 7-bit encoded int. 길이 접두사에 쓴다.
     *
     * <p>하위 7비트씩 쌓고, 최상위 비트가 "뒤에 더 있음"을 뜻한다.
     * {@code & 0x7F} 와 {@code & 0x80} 둘 다 부호 없는 값을 전제하므로
     * {@link #readByte()} 를 통해서만 읽는다.
     */
    public int readLength() {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            int b = readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new NrbfException("7-bit encoded int 가 5바이트를 넘었다 (pos=" + pos + ")");
    }

    /** 길이 접두 UTF-8 문자열. 길이는 문자 수가 아니라 <b>바이트 수</b>다. */
    public String readString() {
        return new String(readBytes(readLength()), StandardCharsets.UTF_8);
    }

    /** UTF-8 문자 하나. 선두 바이트로 길이를 판정한다. */
    public String readChar() {
        require(1);
        int first = buf[pos] & 0xFF;
        int n = first < 0x80 ? 1 : first < 0xE0 ? 2 : first < 0xF0 ? 3 : 4;
        return new String(readBytes(n), StandardCharsets.UTF_8);
    }

    private void require(int n) {
        if (pos + n > end) {
            throw new NrbfException(
                    n + "바이트를 읽으려 했으나 " + (end - pos) + "바이트만 남음 (pos=" + pos + ")");
        }
    }
}
