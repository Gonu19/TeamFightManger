package com.teamfighter.tfm.parser.nrbf;

/**
 * MemberReference. 아직 읽지 않은 객체를 가리킬 수 있으므로
 * 파싱 중에는 자리표시자로 두고 끝난 뒤 한 번에 해소한다.
 */
public record NrbfRef(int id) {
}
