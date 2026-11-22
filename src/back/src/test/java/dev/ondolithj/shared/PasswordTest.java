package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 비밀번호 규칙(1-B2 스펙 4절). 설치와 사용자 생성이 같은 함수를 쓴다. */
class PasswordTest {

	@Test
	void 없거나_10자_미만이면_오류() {
		assertThat(Password.error(null)).contains("필수입니다");
		assertThat(Password.error("a".repeat(9))).contains("10자 이상");
		assertThat(Password.error("a".repeat(10))).isEmpty();
	}

	@Test
	void UTF_8_72바이트를_넘으면_오류() {
		assertThat(Password.error("a".repeat(72))).isEmpty();
		assertThat(Password.error("a".repeat(73))).contains("72바이트 이하(한글은 약 24자)");
		assertThat(Password.error("가".repeat(25))).contains("72바이트 이하(한글은 약 24자)"); // 75바이트
	}
}
