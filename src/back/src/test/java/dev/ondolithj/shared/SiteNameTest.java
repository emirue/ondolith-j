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
			assertThat(SiteName.error(v, 100)).as("[%s]", v).contains("필수입니다");
		}
	}

	@Test
	void 길이는_앞뒤를_뺀_뒤_잰다() {
		assertThat(SiteName.error("  " + "가".repeat(100) + " ", 100)).isEmpty();
		assertThat(SiteName.error("가".repeat(101), 100)).contains("100자 이하");
		assertThat(SiteName.error("가", 100)).isEmpty();
	}

	@Test
	void 상한은_인자로_받는다() {
		// 사용자 표시 이름은 50자(1-B2 스펙 4절, Q9)
		assertThat(SiteName.error("가".repeat(51), 50)).contains("50자 이하");
		assertThat(SiteName.error("가".repeat(50), 50)).isEmpty();
	}

	@Test
	void 가운데에_제어_문자가_있으면_거부한다() {
		// NUL 은 PostgreSQL text 가 받지 않아 500 이 났다. 줄바꿈·탭도 사이트 이름에 쓸 일이 없다
		for (String v : new String[] { "a\u0000b", "a\nb", "a\tb", "a\u007Fb" }) {
			assertThat(SiteName.error(v, 100)).as("[%s]", v).contains("쓸 수 없는 문자가 있습니다");
		}
	}
}
