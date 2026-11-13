package dev.ondolithj.shared;

import java.util.Map;

/** 애너테이션 밖의 검사로 생긴 칸별 오류. ApiErrors 가 422 로 바꾼다. */
public class FieldErrors extends RuntimeException {

	private final Map<String, String> errors;

	public FieldErrors(Map<String, String> errors) {
		super("입력이 올바르지 않습니다", null, false, false);
		this.errors = Map.copyOf(errors);
	}

	public Map<String, String> errors() {
		return errors;
	}
}
