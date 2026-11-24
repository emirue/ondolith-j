package dev.ondolithj.shared;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 링크 값 검사기(1-C1 스펙 4절). 나쁜 꼴을 찾지 않고 정해 둔 두 꼴과 정확히 맞을 때만 받는다(허용 목록).
 * 정화기의 a[href] 와 1-C4 메뉴가 같이 쓴다. java.net.URI 는 쓰지 않는다 — 브라우저(WHATWG) 파서와 해석이 다르다.
 */
public final class LinkCheck {

	// RFC 3986 주소 글자에서 [ ] ' 를 뺀 것. 브라우저가 퍼센트 인코딩으로 바꾸지 않는 글자만 — 공백·제어 문자·개행이 여기서 떨어진다
	private static final Pattern CHARS = Pattern.compile("[A-Za-z0-9._~!$&()*+,;=:@/?#%-]{1,500}");

	// 모두 matches()(전체 일치)로 본다. find() 의 $ 는 끝 개행 앞에서도 맞는다
	private static final Pattern INTERNAL = Pattern.compile("/(?!/).*");

	private static final Pattern EXTERNAL = Pattern.compile("https?://[A-Za-z0-9.-]+(?::([0-9]{1,5}))?(?:[/?#].*)?");

	// WHATWG 파서는 %2e 를 점으로 본다: /%2e//evil 은 풀면 //evil 이다
	private static final Pattern DOT_SEGMENT = Pattern.compile("(?i)(?:\\.|%2e){1,2}");

	private LinkCheck() {
	}

	public static boolean ok(String value) {
		if (value == null || !CHARS.matcher(value).matches()) {
			return false;
		}
		if (INTERNAL.matcher(value).matches()) {
			for (String segment : value.split("[?#]", 2)[0].split("/")) {
				if (DOT_SEGMENT.matcher(segment).matches()) {
					return false;
				}
			}
			return true;
		}
		Matcher m = EXTERNAL.matcher(value);
		if (!m.matches()) {
			return false;
		}
		return m.group(1) == null || (Integer.parseInt(m.group(1)) >= 1 && Integer.parseInt(m.group(1)) <= 65535);
	}
}
