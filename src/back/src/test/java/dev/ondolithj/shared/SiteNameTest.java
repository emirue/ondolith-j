package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 사이트 이름 규칙(1-B1 스펙 8절). 설치와 A-201 이 같은 함수를 쓴다. */
class SiteNameTest {

	@Test
	void 앞뒤의_공백과_NBSP_를_빼고_가운데는_둔다() {
		assertThat(SiteName.strip("  이름  ")).isEqualTo("이름");
		assertThat(SiteName.strip("a b")).isEqualTo("a b");
		assertThat(SiteName.strip(null)).isEmpty();
	}

	@Test
	void 비었거나_공백_보이지_않는_문자만이면_필수입니다() {
		for (String v : new String[] { null, "", "   ", "  ", "​" }) {
			assertThat(SiteName.error(v)).as("[%s]", v).contains("필수입니다");
		}
	}

	@Test
	void 길이는_앞뒤를_뺀_뒤_잰다() {
		assertThat(SiteName.error("  " + "가".repeat(100) + " ")).isEmpty();
		assertThat(SiteName.error("가".repeat(101))).contains("100자 이하");
		assertThat(SiteName.error("가")).isEmpty();
	}
}
