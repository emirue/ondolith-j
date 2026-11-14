package dev.ondolithj.auth;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * 메모리 안 1분 고정 창 셈(ondolith D15 4.3-2: 재시작 때 초기화는 받아들인다). 창이 바뀌면 지난 창의 셈을 통째로 버리므로
 * 메모리는 1분 동안 본 키 수까지만 쓴다.
 * ponytail: 전역 잠금 하나. 단일 인스턴스·요청 수가 적어 충분하다 — 경합이 보이면 키별 원자 셈으로 바꾼다.
 */
public final class RateLimiter {

	public record Decision(boolean allowed, long retryAfterSeconds) {
	}

	private static final long WINDOW_MS = 60_000;

	private final Clock clock;

	private long window = Long.MIN_VALUE;

	private Map<String, Integer> counts = new HashMap<>();

	public RateLimiter(Clock clock) {
		this.clock = clock;
	}

	public synchronized Decision hit(String key, int limitPerMinute) {
		long now = clock.millis();
		long current = Math.floorDiv(now, WINDOW_MS);
		if (current != window) {
			window = current;
			counts = new HashMap<>();
		}
		int count = counts.merge(key, 1, Integer::sum);
		if (count <= limitPerMinute) {
			return new Decision(true, 0);
		}
		long untilNext = (current + 1) * WINDOW_MS - now;
		return new Decision(false, Math.max(1, (untilNext + 999) / 1000));
	}
}
