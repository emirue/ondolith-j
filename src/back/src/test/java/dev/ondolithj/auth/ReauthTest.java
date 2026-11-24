package dev.ondolithj.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 재인증 창 판정(1-B2 스펙 3.1): t ≤ 지금 < t + 15분. */
class ReauthTest {

	static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");

	@Test
	void 찍은_때부터_15분_직전까지_열린다() {
		assertThat(Reauth.open(T0, T0)).isTrue();
		assertThat(Reauth.open(T0, T0.plus(Duration.ofMinutes(15)).minusMillis(1))).isTrue();
	}

	@Test
	void 십오_분이면_닫힌다() {
		assertThat(Reauth.open(T0, T0.plus(Duration.ofMinutes(15)))).isFalse();
	}

	@Test
	void 미래_값은_닫힘이다() {
		assertThat(Reauth.open(T0, T0.minusMillis(1))).isFalse();
	}

	@Test
	void 없거나_타입이_다르면_닫힘이다() {
		assertThat(Reauth.open(null, T0)).isFalse();
		assertThat(Reauth.open("2026-11-22T00:00:00Z", T0)).isFalse();
	}

	@Test
	void 요청_레코드의_toString_은_비밀번호를_가린다() {
		assertThat(new ReauthController.ReauthRequest("tostring-secret-5e9a").toString()).doesNotContain("tostring-secret-5e9a");
	}
}
