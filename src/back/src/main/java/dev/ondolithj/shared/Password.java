package dev.ondolithj.shared;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** 비밀번호 규칙(1-B2 스펙 4절). 설치와 사용자 생성이 같이 쓴다. 확인 칸은 프런트에만 있다. */
public final class Password {

	private Password() {
	}

	public static Optional<String> error(String raw) {
		if (raw == null) {
			return Optional.of("필수입니다");
		}
		if (raw.length() < 10) {
			return Optional.of("10자 이상");
		}
		// BCrypt 는 72바이트 뒤를 버린다 — 넘는 부분이 비밀번호에 들지 않는 것을 막는다
		if (raw.getBytes(StandardCharsets.UTF_8).length > 72) {
			return Optional.of("72바이트 이하(한글은 약 24자)");
		}
		return Optional.empty();
	}
}
