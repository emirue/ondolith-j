package dev.ondolithj.shared;

import org.springframework.http.HttpStatus;

/** 403·404·409 거절. detail 은 코드 상수다(요청 값을 섞지 않는다). ApiErrors 가 Problem Details 로 바꾼다. */
public final class Refused extends RuntimeException {

	private final HttpStatus status;

	public Refused(HttpStatus status, String detail) {
		super(detail, null, false, false);
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
