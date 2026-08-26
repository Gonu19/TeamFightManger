package com.teamfighter.tfm.ingest.watcher;

import java.util.function.BooleanSupplier;

/**
 * 워처 테스트 공용 폴링 헬퍼.
 *
 * <p>디바운스 시간을 짧게(테스트에서는 120ms) 주입해 결정적으로 만들되, "그 시간이 지났는지"는
 * 실시간으로 확인할 수밖에 없다 — 이건 스레드 스케줄링의 실제 지연이지 로직으로 흉내 낼 수 없다.
 * 대신 고정 sleep 대신 폴링 + 타임아웃으로 짧은 슬랙만 흡수한다. Awaitility 는 새로 추가하지
 * 않는다(절대 규칙 5) — build.gradle 에 이미 있는 것만 쓴다.
 */
final class WatcherTestSupport {

    private WatcherTestSupport() {
    }

    static void awaitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("타임아웃(" + timeoutMs + "ms) 안에 조건이 충족되지 않았다");
            }
            sleepQuietly(20);
        }
    }

    /** "이 조건이 계속 거짓으로 유지된다"를 확인한다 — 즉 이 기간 안에는 불리면 안 된다는 단언. */
    static void assertStaysFalseFor(BooleanSupplier condition, long durationMs) {
        long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                throw new AssertionError("조건이 " + durationMs + "ms 안에 참이 되면 안 되는데 참이 됐다");
            }
            sleepQuietly(20);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("대기 중 인터럽트", e);
        }
    }
}
