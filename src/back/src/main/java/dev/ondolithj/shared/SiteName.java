package dev.ondolithj.shared;

import java.util.Optional;
import java.util.regex.Pattern;

/** 사이트 이름 규칙(1-B1 스펙 8절). 설치·A-201(100자)과 사용자 표시 이름(50자, 1-B2 스펙 4절)이 같이 쓰고, 프런트가 같은 세 정규식을 쓴다. */
public final class SiteName {

	// String.strip() 은 NBSP(U+00A0)를 남긴다 — \p{Z} 로 뺀다
	private static final Pattern EDGES = Pattern.compile("^[\\p{Z}\\t\\n\\x0B\\f\\r]+|[\\p{Z}\\t\\n\\x0B\\f\\r]+$");

	// 공백·보이지 않는 문자(영폭 공백 U+200B 등)만 남으면 비어 있는 것으로 본다
	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Z}\\p{C}]*");

	// 제어 문자(NUL·줄바꿈·탭·DEL 등). NUL 은 PostgreSQL text 가 받지 않아 저장이 500 이 됐다
	private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

	private SiteName() {
	}

	public static String strip(String raw) {
		return raw == null ? "" : EDGES.matcher(raw).replaceAll("");
	}

	public static Optional<String> error(String raw, int max) {
		String v = strip(raw);
		if (INVISIBLE.matcher(v).matches()) {
			return Optional.of("필수입니다");
		}
		if (CONTROL.matcher(v).find()) {
			return Optional.of("쓸 수 없는 문자가 있습니다");
		}
		return v.length() > max ? Optional.of(max + "자 이하") : Optional.empty();
	}
}
