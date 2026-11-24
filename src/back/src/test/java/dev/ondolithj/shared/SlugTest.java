package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 슬러그 규칙(1-C1 스펙 5.1·5.2). 고치지 않고 틀리면 거부한다. */
class SlugTest {

	@Test
	void 없거나_빈_값은_필수입니다() {
		assertThat(Slug.error(null)).contains("필수입니다");
		assertThat(Slug.error("")).contains("필수입니다");
	}

	@Test
	void 길이는_100자까지() {
		assertThat(Slug.error("a".repeat(101))).contains("100자 이하");
		assertThat(Slug.error("a".repeat(100))).isEmpty();
	}

	@Test
	void 형식이_아니면_소문자_숫자_하이픈_문구() {
		for (String v : new String[] { "Foo", "-a", "a/b", "a.b", "a_b", "한글", "about\n", " a", "a b" }) {
			assertThat(Slug.error(v)).as("[%s]", v).contains("소문자·숫자·하이픈만 쓸 수 있습니다");
			assertThat(Slug.valid(v)).as("[%s]", v).isFalse();
		}
	}

	@Test
	void 예약어는_사용할_수_없는_주소입니다() {
		for (String v : new String[] { "admin", "api", "assets", "error", "login" }) {
			assertThat(Slug.error(v)).as(v).contains("사용할 수 없는 주소입니다");
			assertThat(Slug.valid(v)).as("형식은 맞다: " + v).isTrue();
		}
	}

	@Test
	void 맞는_슬러그는_통과한다() {
		for (String v : new String[] { "a", "0-a", "about-us", "a-" }) {
			assertThat(Slug.error(v)).as(v).isEmpty();
			assertThat(Slug.valid(v)).as(v).isTrue();
		}
		assertThat(Slug.valid(null)).isFalse();
		assertThat(Slug.valid("")).isFalse();
		assertThat(Slug.valid("a".repeat(101))).isFalse();
	}

	@Test
	void 예약어_목록은_스물넷이다() {
		for (String v : new String[] { "admin", "login", "logout", "signup", "password", "auth", "me", "verify", "board",
				"comments", "attachments", "search", "shop", "cart", "checkout", "orders", "webhooks", "static", "install",
				"sitemap.xml", "robots.txt", "api", "assets", "error" }) {
			assertThat(Slug.reserved(v)).as(v).isTrue();
		}
		assertThat(Slug.reserved("about")).isFalse();
		assertThat(Slug.reserved("# 주석")).isFalse();
		assertThat(Slug.reserved("")).isFalse();
	}

}
