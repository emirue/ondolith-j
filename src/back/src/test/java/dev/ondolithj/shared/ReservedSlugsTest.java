package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.auth.AuthFixture;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 예약어 목록이 서버 라우트와 어긋나지 않는다(1-C1 스펙 5.1, Q5). 프런트 라우트 쪽은 프런트 시험이 본다. */
class ReservedSlugsTest {

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	@Test
	void 운영_컨텍스트의_모든_MVC_매핑_첫_마디가_예약어다() {
		Set<String> first = new TreeSet<>();
		f.ctx.getBeansOfType(RequestMappingHandlerMapping.class)
			.values()
			.forEach(m -> m.getHandlerMethods().forEach((info, h) -> {
				// springdoc(/v3/api-docs)은 시험 클래스패스에만 있다 — 운영 jar 의 라우트가 아니다(TestOnlyAllowance 와 같다)
				if (!h.getBeanType().getPackageName().startsWith("org.springdoc")) {
					info.getPatternValues().forEach(p -> first.add(p.split("/", 3)[1]));
				}
			}));
		// 비어서 지나가지 않게: 지금 있는 두 마디를 실제로 모았다
		assertThat(first).contains("api", "error");
		assertThat(first).allSatisfy(s -> assertThat(Slug.reserved(s)).as(s).isTrue());
	}

}
