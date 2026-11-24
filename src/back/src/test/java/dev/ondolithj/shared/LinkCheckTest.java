package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 링크 검사기(1-C1 스펙 4절). 표 13.3 은 1-C2·1-C4 의 시험도 같은 파일을 읽는다. */
class LinkCheckTest {

	record Links(List<String> reject, List<String> allow) {
	}

	static final Links LINKS = load();

	static Links load() {
		try (InputStream in = LinkCheckTest.class.getResourceAsStream("/richtext/links.json")) {
			return JsonMapper.builder().build().readValue(in, Links.class);
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void 거부_표의_모든_값은_false() {
		assertThat(LINKS.reject()).hasSize(40);
		for (String v : LINKS.reject()) {
			assertThat(LinkCheck.ok(v)).as("[" + v + "]").isFalse();
		}
	}

	@Test
	void 허용_표의_모든_값은_true() {
		assertThat(LINKS.allow()).hasSize(9);
		for (String v : LINKS.allow()) {
			assertThat(LinkCheck.ok(v)).as("[" + v + "]").isTrue();
		}
	}

	@Test
	void null_은_false() {
		assertThat(LinkCheck.ok(null)).isFalse();
	}

	@Test
	void 길이_501자는_false_500자_내부_경로는_true() {
		assertThat(LinkCheck.ok("/" + "a".repeat(499))).isTrue();
		assertThat(LinkCheck.ok("/" + "a".repeat(500))).isFalse();
	}

	@Test
	void 섞인_점_마디와_포트_경계() {
		assertThat(LinkCheck.ok("/.%2E//evil.example")).isFalse();
		assertThat(LinkCheck.ok("/a/..")).isFalse();
		assertThat(LinkCheck.ok("/a/%2e")).isFalse();
		// ? · # 뒤의 점은 경로 마디가 아니다
		assertThat(LinkCheck.ok("/a?x=/../b")).isTrue();
		assertThat(LinkCheck.ok("/a#/./b")).isTrue();
		assertThat(LinkCheck.ok("http://a.example:1/")).isTrue();
		assertThat(LinkCheck.ok("http://a.example:65536/")).isFalse();
	}

	@Test
	void 정규식_끝_개행은_find_에서_맞는다_그래서_matches_로_본다() {
		// 스펙 3.1 「구현 때 확인」: Java 의 $ 는 find() 에서 마지막 개행 앞에서도 맞는다
		assertThat(Pattern.compile("^/(?!/).*$").matcher("/about\n").find()).isTrue();
		assertThat(Pattern.compile("^/(?!/).*$").matcher("/about\n").matches()).isFalse();
	}

}
