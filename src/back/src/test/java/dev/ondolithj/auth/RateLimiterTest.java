package dev.ondolithj.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

	/** 시험이 돌리는 시계. */
	static final class FakeClock extends Clock {

		long millis;

		FakeClock(long millis) {
			this.millis = millis;
		}

		@Override
		public long millis() {
			return millis;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(millis);
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}

	@Test
	void 한도까지_허용하고_넘으면_창이_끝날_때까지_기다리라고_한다() {
		FakeClock clock = new FakeClock(60_000L * 1000 + 15_000); // 창 시작 15초 뒤
		RateLimiter limiter = new RateLimiter(clock);
		for (int i = 0; i < 10; i++) {
			assertThat(limiter.hit("k", 10).allowed()).isTrue();
		}
		RateLimiter.Decision d = limiter.hit("k", 10);
		assertThat(d.allowed()).isFalse();
		assertThat(d.retryAfterSeconds()).isEqualTo(45);
	}

	@Test
	void 창이_바뀌면_회복한다() {
		FakeClock clock = new FakeClock(60_000L * 1000 + 59_000);
		RateLimiter limiter = new RateLimiter(clock);
		assertThat(limiter.hit("k", 1).allowed()).isTrue();
		assertThat(limiter.hit("k", 1).allowed()).isFalse();
		clock.millis += 1_000;
		assertThat(limiter.hit("k", 1).allowed()).isTrue();
	}

	@Test
	void 키는_서로_독립이다() {
		RateLimiter limiter = new RateLimiter(new FakeClock(0));
		assertThat(limiter.hit("a", 1).allowed()).isTrue();
		assertThat(limiter.hit("a", 1).allowed()).isFalse();
		assertThat(limiter.hit("b", 1).allowed()).isTrue();
	}

	@Test
	void 동시에_두드려도_정확히_한도만_허용한다() throws Exception {
		RateLimiter limiter = new RateLimiter(new FakeClock(0));
		AtomicInteger allowed = new AtomicInteger();
		CountDownLatch go = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
			for (int i = 0; i < 100; i++) {
				pool.submit(() -> {
					go.await();
					if (limiter.hit("k", 50).allowed()) {
						allowed.incrementAndGet();
					}
					return null;
				});
			}
			go.countDown();
		}
		assertThat(allowed.get()).isEqualTo(50);
	}

	@Test
	void IP_한도를_넘은_로그인은_계정_키를_세지_않는다() {
		RateLimiter limiter = new RateLimiter(new FakeClock(0));
		RateLimitProperties limits = new RateLimitProperties(1, 1, 60, 5);
		assertThat(LoginController.limit(limiter, limits, "10.0.0.1", "a@ondolith.test")).isEmpty();
		assertThat(LoginController.limit(limiter, limits, "10.0.0.1", "b@ondolith.test")).isPresent(); // IP 에서 막힘
		// b 는 세지 않았으므로 다른 IP 에서 b 의 첫 시도는 허용된다
		assertThat(LoginController.limit(limiter, limits, "10.0.0.2", "b@ondolith.test")).isEmpty();
	}
}
