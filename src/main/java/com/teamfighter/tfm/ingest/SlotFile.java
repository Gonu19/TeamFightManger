package com.teamfighter.tfm.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 세이브 파일 경로의 판정과 해시.
 *
 * <p><b>{@code slot_*.tfm} 만 슬롯이다.</b> 같은 폴더에 {@code slot_*.tfm_backup} 이 있는데,
 * 이것을 슬롯으로 잡으면 같은 커리어가 두 벌 적재된다. {@code ingest_run} 의 해시 중복 검사는
 * {@code (slot_id, file_hash)} 라 <b>슬롯 안에서만</b> 돌고, 백업은 {@code slot_key} 가 달라
 * 다른 슬롯이 되므로 내용이 같아도 걸러지지 않는다 (D28).
 *
 * <p>그래서 확장자 <b>완전 일치</b>로 판정한다. "이름에 .tfm 이 들어 있으면" 같은 느슨한 검사는
 * {@code .tfm_backup} 과 {@code .tfm.bak} 을 둘 다 통과시킨다.
 */
public final class SlotFile {

    private static final Pattern SLOT_NAME = Pattern.compile("^slot_[^/\\\\]+\\.tfm$");

    private SlotFile() {
    }

    /** 슬롯 키 = 파일명. 형식이 아니면 던진다. */
    public static String slotKeyOf(Path saveFile) {
        String name = saveFile.getFileName().toString();
        if (!SLOT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "슬롯 파일이 아니다: " + name
                            + ". slot_*.tfm 만 받는다 — *.tfm_backup 을 슬롯으로 잡으면 "
                            + "같은 커리어가 두 벌 적재된다 (D28)");
        }
        return name;
    }

    public static boolean isSlotFile(Path path) {
        return SLOT_NAME.matcher(path.getFileName().toString()).matches();
    }

    /** 내용 해시. 같은 내용을 다시 적재하지 않기 위한 것이다. */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
