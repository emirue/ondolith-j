package dev.ondolithj.shared;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 페이지 슬러그 규칙(1-C1 스펙 5.1·5.2). 고치지 않고(소문자화·공백 빼기 없음) 틀리면 거부한다. */
public final class Slug {

	// matches()(전체 일치)로 본다 — find() 면 about\n 이 지나가 DB CHECK 에서 500 이 된다
	public static final Pattern FORMAT = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

	private static final int MAX = 100;

	private static final Set<String> RESERVED = load();

	private Slug() {
	}

	private static Set<String> load() {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				Slug.class.getResourceAsStream("/reserved-slugs.txt"), StandardCharsets.UTF_8))) {
			return r.lines()
				.map(String::strip)
				.filter(l -> !l.isEmpty() && !l.startsWith("#"))
				.collect(Collectors.toUnmodifiableSet());
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static boolean valid(String s) {
		return s != null && !s.isEmpty() && s.length() <= MAX && FORMAT.matcher(s).matches();
	}

	public static boolean reserved(String s) {
		return RESERVED.contains(s);
	}

	public static Optional<String> error(String raw) {
		if (raw == null || raw.isEmpty()) {
			return Optional.of("필수입니다");
		}
		if (raw.length() > MAX) {
			return Optional.of("100자 이하");
		}
		if (!FORMAT.matcher(raw).matches()) {
			return Optional.of("소문자·숫자·하이픈만 쓸 수 있습니다");
		}
		return reserved(raw) ? Optional.of("사용할 수 없는 주소입니다") : Optional.empty();
	}
}
