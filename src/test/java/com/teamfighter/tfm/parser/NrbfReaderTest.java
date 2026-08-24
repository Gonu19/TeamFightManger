package com.teamfighter.tfm.parser;

import com.teamfighter.tfm.parser.nrbf.NrbfReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 바이트 수준 계약을 고정한다.
 *
 * <p><b>골든 파일 테스트로는 이걸 대신할 수 없다.</b> 실제로 확인했다 —
 * {@code readByte()} 에서 {@code & 0xFF} 를 빼고 돌려도 골든 테스트가 통과한다.
 * 세이브 파일에 0x80 이상인 바이트가 538회 넘게 나오지만 전부 {@code readLength()}
 * 안에서만 쓰이고, 그 함수는 내부에서 {@code & 0x7F} 로 다시 마스킹하기 때문이다.
 *
 * <p>즉 골든 파일은 <b>표본이 우연히 담고 있는 것</b>만 지킨다.
 * 부호 처리 같은 계약은 여기서 직접 못 박아야 한다.
 */
class NrbfReaderTest {

    private static NrbfReader reader(int... bytes) {
        byte[] buf = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            buf[i] = (byte) bytes[i];
        }
        return new NrbfReader(buf, 0, buf.length);
    }

    @Test
    @DisplayName("readByte 는 0x80 이상을 양수로 돌려준다 — Java 의 byte 는 부호가 있다")
    void readByte_highBitSet_returnsUnsigned() {
        NrbfReader r = reader(0x00, 0x7F, 0x80, 0xFE, 0xFF);

        assertThat(r.readByte()).isEqualTo(0);
        assertThat(r.readByte()).isEqualTo(127);
        assertThat(r.readByte()).isEqualTo(128);   // 부호 확장하면 -128
        assertThat(r.readByte()).isEqualTo(254);   // 부호 확장하면 -2
        assertThat(r.readByte()).isEqualTo(255);   // 부호 확장하면 -1
    }

    @Test
    @DisplayName("readSByte 는 부호를 유지한다 — SByte 는 실제로 음수를 담는다")
    void readSByte_keepsSign() {
        NrbfReader r = reader(0xFF, 0x80, 0x7F);

        assertThat(r.readSByte()).isEqualTo((byte) -1);
        assertThat(r.readSByte()).isEqualTo((byte) -128);
        assertThat(r.readSByte()).isEqualTo((byte) 127);
    }

    @Test
    @DisplayName("readLength 는 7-bit encoded int 를 여러 바이트로 읽는다")
    void readLength_multiByte() {
        assertThat(reader(0x00).readLength()).isZero();
        assertThat(reader(0x7F).readLength()).isEqualTo(127);
        assertThat(reader(0x80, 0x01).readLength()).isEqualTo(128);
        assertThat(reader(0xAC, 0x02).readLength()).isEqualTo(300);
        assertThat(reader(0xFF, 0x7F).readLength()).isEqualTo(16383);
    }

    @Test
    @DisplayName("readLength 가 5바이트를 넘으면 조용히 넘기지 않고 던진다")
    void readLength_runaway_throws() {
        assertThatThrownBy(() -> reader(0x80, 0x80, 0x80, 0x80, 0x80, 0x80).readLength())
                .hasMessageContaining("5바이트");
    }

    @Test
    @DisplayName("readString 의 길이 접두사는 문자 수가 아니라 바이트 수다")
    void readString_lengthIsBytes_notChars() {
        byte[] text = "격투가".getBytes(StandardCharsets.UTF_8);   // 3글자 · 9바이트
        assertThat(text).hasSize(9);

        int[] bytes = new int[1 + text.length];
        bytes[0] = text.length;
        for (int i = 0; i < text.length; i++) {
            bytes[i + 1] = text[i] & 0xFF;
        }

        assertThat(reader(bytes).readString()).isEqualTo("격투가");
    }

    @Test
    @DisplayName("128바이트가 넘는 문자열도 읽는다 — 길이 접두사가 2바이트가 된다")
    void readString_longerThan127_usesMultiByteLength() {
        String expected = "System.Collections.Generic.List`1[[ChampionExp, Scripts, "
                + "Version=0.0.0.0, Culture=neutral, PublicKeyToken=null]]"
                + "0123456789012345678901234567890123456789";
        byte[] text = expected.getBytes(StandardCharsets.UTF_8);
        assertThat(text.length).isGreaterThan(127);

        int[] bytes = new int[2 + text.length];
        bytes[0] = (text.length & 0x7F) | 0x80;
        bytes[1] = text.length >> 7;
        for (int i = 0; i < text.length; i++) {
            bytes[i + 2] = text[i] & 0xFF;
        }

        assertThat(reader(bytes).readString()).isEqualTo(expected);
    }

    @Test
    @DisplayName("정수는 리틀엔디언이고 음수를 제대로 복원한다")
    void readIntegers_littleEndian() {
        assertThat(reader(0x01, 0x00, 0x00, 0x00).readInt32()).isEqualTo(1);
        assertThat(reader(0xFF, 0xFF, 0xFF, 0xFF).readInt32()).isEqualTo(-1);
        assertThat(reader(0x00, 0x01, 0x00, 0x00).readInt32()).isEqualTo(256);
        assertThat(reader(0xFF, 0xFF, 0xFF, 0xFF).readUInt32()).isEqualTo(4294967295L);
        assertThat(reader(0xFF, 0xFF).readInt16()).isEqualTo((short) -1);
        assertThat(reader(0xFF, 0xFF).readUInt16()).isEqualTo(65535);
    }

    @Test
    @DisplayName("버퍼를 넘어 읽으면 조용히 0 을 주지 않고 던진다")
    void read_pastEnd_throws() {
        assertThatThrownBy(() -> reader(0x01, 0x02).readInt32())
                .hasMessageContaining("바이트만 남음");
    }
}
