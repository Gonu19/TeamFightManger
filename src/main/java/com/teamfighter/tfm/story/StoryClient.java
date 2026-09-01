package com.teamfighter.tfm.story;

/**
 * 모델 호출 하나. 구현이 Groq·Cerebras·로컬을 가른다.
 *
 * <p><b>인터페이스가 하나뿐인 이유.</b> 셋 다 OpenAI 호환이라 {@code base-url} 만 다르다.
 * 제공자를 바꾸는 일이 설정 한 줄이 되도록 여기서 막아둔다.
 *
 * <p><b>실패를 삼키지 않는다.</b> 키가 없거나 꺼져 있으면 빈 문자열을 돌려주는 대신
 * 던진다. 조용히 안 도는 것이 이 프로젝트에서 가장 비싼 실패였다 (D31).
 */
public interface StoryClient {

    /**
     * 요청 하나를 보내고 본문을 받는다.
     *
     * @throws StoryUnavailableException 꺼져 있거나 키가 없을 때
     * @throws StoryFailedException      호출이 실패했을 때
     */
    String complete(StoryRequest request);

    /** 지금 부를 수 있는 상태인가. 화면이 버튼을 감출 때 쓴다. */
    boolean isAvailable();

    /** 꺼져 있거나 키가 없다 — 부를 수 없는 상태. */
    class StoryUnavailableException extends RuntimeException {
        public StoryUnavailableException(String message) {
            super(message);
        }
    }

    /** 불렀는데 실패했다. */
    class StoryFailedException extends RuntimeException {

        /**
         * 모델이 돌려준 HTTP 상태. 네트워크·파싱 실패처럼 응답 자체가 없으면 {@code 0}.
         *
         * <p><b>왜 들고 다니나.</b> 화면이 사용자에게 <b>할 일</b>을 말하려면 무엇이
         * 잘못됐는지를 알아야 한다 — 401 은 "키를 확인한다", 429 는 "잠시 뒤 다시",
         * 5xx 는 "저쪽 문제라 기다린다" 로 대응이 전부 다르다. 메시지 문자열에서
         * 숫자를 다시 긁어내는 것은 그 문자열이 바뀌는 순간 조용히 깨진다.
         */
        private final int status;

        public StoryFailedException(String message, Throwable cause) {
            super(message, cause);
            this.status = 0;
        }

        public StoryFailedException(String message) {
            this(message, 0);
        }

        public StoryFailedException(String message, int status) {
            super(message);
            this.status = status;
        }

        /** 모델이 돌려준 HTTP 상태. 응답이 없었으면 {@code 0}. */
        public int status() {
            return status;
        }
    }
}
