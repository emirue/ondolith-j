package dev.ondolithj.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 로그인 입력. 길이 상한을 넘으면 422(1-A 스펙 3.1). */
record LoginRequest(@NotBlank(message = "필수입니다") @Size(max = 254, message = "254자 이하") String email,
		@NotNull(message = "필수입니다") @Size(max = 1000, message = "1000자 이하") String password) {

	/** 비밀번호가 로그(DEBUG 의 요청 본문 등)에 새지 않게 가린다. */
	@Override
	public String toString() {
		return "LoginRequest[]";
	}
}
