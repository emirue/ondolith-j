package dev.ondolithj.shared;

import java.util.Optional;
import java.util.regex.Pattern;

/** 사이트 이름 규칙(1-B1 스펙 8절). 설치와 A-201 이 같이 쓰고, 프런트가 같은 두 정규식을 쓴다. */
public final class SiteName {

	// String.strip() 은 NBSP(U+00A0)를 남긴다 — \p{Z} 로 뺀다
	private static final Pattern EDGES = Pattern.compile("^[\\p{Z}\\t\\n\\x0B\\f\\r]+|[\\p{Z}\\t\\n\\x0B\\f\\r]+$");

	// 공백·보이지 않는 문자(영폭 공백 U+200B 등)만 남으면 비어 있는 것으로 본다
	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Z}\\p{C}]*");

	private SiteName() {
	}

	public static String strip(String raw) {
		return raw == null ? "" : EDGES.matcher(raw).replaceAll("");
	}

	public static Optional<String> error(String raw) {
		String v = strip(raw);
		if (INVISIBLE.matcher(v).matches()) {
			return Optional.of("필수입니다");
		}
		return v.length() > 100 ? Optional.of("100자 이하") : Optional.empty();
	}
}
