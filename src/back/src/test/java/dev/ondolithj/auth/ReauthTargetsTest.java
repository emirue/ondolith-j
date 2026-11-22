package dev.ondolithj.auth;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 처리기 대조(1-B2 스펙 3.4, D15 5.3-1). /api/admin 의 GET 이 아닌 처리기는 모두 두 표 중 하나에 있어야 한다 —
 * 새 상태 변경 API 는 재인증 대상인지 분류를 강제받는다.
 */
class ReauthTargetsTest {

	/** 재인증 대상(D15 5.3-1 의 1-B2 행). Task 4·7 이 채운다. */
	static final Set<String> TARGETS = Set.of("POST /api/admin/users/{id}/deactivate", "POST /api/admin/users/{id}/activate");

	static final Set<String> NOT_TARGETS = Set.of("POST /api/admin/settings", "POST /api/admin/reauth");

	record Ids(UUID user, UUID role) {
	}

	record Call(String path, String body) {
	}

	static final AtomicInteger SEQ = new AtomicInteger();

	static AuthFixture f;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false);
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	/** `<메서드> <패턴>`. 메서드 제한이 없는 매핑은 `ANY <패턴>`. */
	static Set<String> keys(ConfigurableApplicationContext ctx) {
		Map<RequestMappingInfo, HandlerMethod> mappings = new HashMap<>();
		ctx.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(m -> mappings.putAll(m.getHandlerMethods()));
		Set<String> keys = new TreeSet<>();
		mappings.keySet().forEach(info -> {
			Set<String> methods = info.getMethodsCondition().getMethods().stream().map(RequestMethod::name)
				.collect(Collectors.toSet());
			info.getPatternValues().stream().filter(p -> p.startsWith("/api/admin")).forEach(p -> {
				if (methods.isEmpty()) {
					keys.add("ANY " + p);
				}
				methods.stream().filter(m -> !m.equals("GET")).forEach(m -> keys.add(m + " " + p));
			});
		});
		return keys;
	}

	static Set<String> unclassified(ConfigurableApplicationContext ctx) {
		Set<String> keys = keys(ctx);
		keys.removeAll(TARGETS);
		keys.removeAll(NOT_TARGETS);
		return keys;
	}

	/** 키마다 실제로 보낼 요청. */
	static Call call(String key, Ids ids) {
		return switch (key) {
			case "POST /api/admin/settings" -> new Call("/api/admin/settings", "{\"name\": \"이름-" + SEQ.incrementAndGet() + "\"}");
			case "POST /api/admin/reauth" -> new Call("/api/admin/reauth", "{\"password\": \"" + PASSWORD + "\"}");
			case "POST /api/admin/users/{id}/deactivate" -> new Call("/api/admin/users/" + ids.user() + "/deactivate", "");
			case "POST /api/admin/users/{id}/activate" -> new Call("/api/admin/users/" + ids.user() + "/activate", "");
			default -> throw new IllegalStateException("표에 요청을 더한다: " + key);
		};
	}

	/** 재인증 대상이 바꿀 수 있는 것 전부와 작업 로그 건수. */
	static String state() throws Exception {
		try (Connection c = f.db();
				ResultSet r = c.createStatement().executeQuery("""
						SELECT (SELECT md5(coalesce(string_agg(id::text || is_active::text || sessions_valid_from::text, ',' ORDER BY id), '')) FROM users)
						    || (SELECT md5(coalesce(string_agg(user_id::text || role_id::text, ',' ORDER BY user_id, role_id), '')) FROM user_roles)
						    || (SELECT md5(coalesce(string_agg(role_id::text || permission_id::text, ',' ORDER BY role_id, permission_id), '')) FROM role_permissions)
						    || (SELECT count(*) FROM operation_logs)::text
						""")) {
			r.next();
			return r.getString(1);
		}
	}

	static TestHttp admin() throws Exception {
		String email = "targets" + SEQ.incrementAndGet() + "@ondolith.test";
		f.user(email, "admin");
		return f.loginAs(email);
	}

	static Ids ids() throws Exception {
		UUID user = f.user("target" + SEQ.incrementAndGet() + "@ondolith.test");
		try (Connection c = f.db(); PreparedStatement s = c.prepareStatement("SELECT id FROM roles WHERE key = 'editor'");
				ResultSet r = s.executeQuery()) {
			r.next();
			return new Ids(user, r.getObject(1, UUID.class));
		}
	}

	@Test
	void 상태_문자열은_같으면_같고_사용자_행이_바뀌면_달라진다() throws Exception {
		UUID user = ids().user();
		String before = state();
		assertThat(state()).isEqualTo(before);
		f.sql("UPDATE users SET is_active = false WHERE id = ?", user);
		assertThat(state()).isNotEqualTo(before);
	}

	@Test
	void 관리자_경로의_GET_이_아닌_처리기는_모두_분류되고_표의_키는_모두_있다() {
		assertThat(unclassified(f.ctx)).isEmpty();
		assertThat(keys(f.ctx)).containsAll(Stream.concat(TARGETS.stream(), NOT_TARGETS.stream()).toList());
	}

	@Test
	void 분류되지_않은_POST_가_있으면_잡아낸다() throws Exception {
		try (AuthFixture other = new AuthFixture().start(false, "--spring.main.sources=fixture.guard.UnclassifiedPostConfig")) {
			assertThat(unclassified(other.ctx)).containsExactly("POST /api/admin/unclassified-probe");
		}
	}

	@Test
	void 대상은_창이_닫히면_재인증_403_이고_DB_가_그대로다() throws Exception {
		for (String key : TARGETS) {
			TestHttp http = admin();
			Ids ids = ids();
			f.reauthAt(http, null);
			String before = state();
			Call c = call(key, ids);
			ReauthApiTest.reauthRequired(http.post(c.path(), c.body()));
			assertThat(state()).as(key).isEqualTo(before);
		}
	}

	@Test
	void 대상_아님은_창이_닫혀도_재인증_type_을_내지_않는다() throws Exception {
		for (String key : NOT_TARGETS) {
			TestHttp http = admin();
			f.reauthAt(http, null);
			Call c = call(key, ids());
			HttpResponse<String> r = http.post(c.path(), c.body());
			assertThat(r.statusCode()).as(key).isBetween(200, 299);
			assertThat(r.body()).as(key).doesNotContain(ReauthApiTest.URN);
		}
	}
}
