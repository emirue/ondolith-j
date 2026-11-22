package dev.ondolithj;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.config.ConfigFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 두 컨텍스트의 /v3/api-docs 를 합쳐 src/front/openapi.json 으로 쓴다(springdoc 은 시험 의존성만 — 운영 jar 에 없다).
 * 프런트는 이 파일로 타입을 만들고, CI 는 커밋된 파일과 차이가 있으면 실패한다.
 */
@Testcontainers
class OpenApiFileTest {

	static final Path OUT = Path.of("../front/openapi.json");

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine");

	@TempDir
	Path dir;

	final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

	JsonNode docs(ConfigurableApplicationContext ctx) throws Exception {
		return json.readTree(TestHttp.of(ctx).get("/v3/api-docs").body());
	}

	@Test
	void 문서가_세_경로를_담고_snake_case_이다() throws Exception {
		JsonNode install;
		try (var ctx = Launcher.startInstall(dir.resolve("ondolith-j.properties"), "--server.port=0")) {
			install = docs(ctx);
		}
		JsonNode running;
		ConfigFile config = new ConfigFile(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(),
				URI.create("http://site.ondolith.test"), false, ConfigFile.newSealKey(), dir.resolve("u"), dir.resolve("b"),
				Instant.now());
		try (var ctx = Launcher.startRunning(config, "--server.port=0")) {
			running = docs(ctx);
		}
		ObjectNode merged = (ObjectNode) install.deepCopy();
		merge((ObjectNode) merged.get("paths"), running.get("paths"));
		merge((ObjectNode) merged.withObject("/components").withObject("/schemas"), running.at("/components/schemas"));
		merged.remove("servers"); // 시험마다 바뀌는 임시 포트
		snakeCase(merged.at("/components/schemas"));
		problemMembers(merged.at("/components/schemas/ProblemDetail"));
		Files.writeString(OUT, json.writeValueAsString(sorted(merged)) + "\n");

		JsonNode written = json.readTree(Files.readString(OUT));
		assertThat(fieldNames(written.get("paths"))).containsExactly("/api/admin", "/api/admin/oplog", "/api/admin/reauth", "/api/admin/settings", "/api/admin/system", "/api/admin/users", "/api/admin/users/{id}", "/api/admin/users/{id}/activate", "/api/admin/users/{id}/deactivate", "/api/auth/login", "/api/auth/logout", "/api/health", "/api/install", "/api/me", "/api/site");
		assertThat(fieldNames(written.at("/components/schemas/InstallRequest/properties"))).contains("db_host",
				"admin_password_confirm");
		// 프런트가 응답을 캐스팅하지 않게, 실제로 내는 상태 코드와 Problem Details 본문을 문서에 담는다
		JsonNode responses = written.at("/paths/~1api~1install/post/responses");
		assertThat(fieldNames(responses)).containsExactlyInAnyOrder("202", "400", "403", "404", "409", "422", "500");
		assertThat(responses.at("/202/content").isMissingNode()).isTrue();
		// CSRF 거부도 Problem Details 다(1-A 스펙 6절, SecurityConfig 의 거부 처리기)
		for (String code : java.util.List.of("400", "403", "404", "409", "422", "500")) {
			assertThat(responses.at("/" + code + "/content/application~1problem+json/schema/$ref").asString())
				.as(code).isEqualTo("#/components/schemas/ProblemDetail");
		}
		// 로그인·관리자 API 의 실제 응답 코드(1-A 스펙 6절)
		JsonNode login = written.at("/paths/~1api~1auth~1login/post/responses");
		assertThat(fieldNames(login)).containsExactlyInAnyOrder("204", "401", "403", "422", "429");
		JsonNode admin = written.at("/paths/~1api~1admin/get/responses");
		assertThat(fieldNames(admin)).containsExactlyInAnyOrder("200", "401", "403", "429");
		for (JsonNode r : java.util.List.of(login.get("401"), login.get("429"), admin.get("401"), admin.get("403"))) {
			assertThat(r.at("/content/application~1problem+json/schema/$ref").asString())
				.isEqualTo("#/components/schemas/ProblemDetail");
		}
		// A-201(1-B1 스펙 5절)
		JsonNode settings = written.at("/paths/~1api~1admin~1settings/post/responses");
		assertThat(fieldNames(settings)).containsExactlyInAnyOrder("204", "400", "401", "403", "422", "429");
		assertThat(settings.at("/204/content").isMissingNode()).isTrue();
		for (String code : java.util.List.of("401", "403", "422")) {
			assertThat(settings.at("/" + code + "/content/application~1problem+json/schema/$ref").asString())
				.as(code).isEqualTo("#/components/schemas/ProblemDetail");
		}
		// A-601·A-602(1-B1 스펙 5절)
		JsonNode oplog = written.at("/paths/~1api~1admin~1oplog/get/responses");
		assertThat(fieldNames(oplog)).containsExactlyInAnyOrder("200", "400", "401", "403", "422", "429");
		JsonNode system = written.at("/paths/~1api~1admin~1system/get/responses");
		assertThat(fieldNames(system)).containsExactlyInAnyOrder("200", "401", "403", "429");
		for (JsonNode r : java.util.List.of(oplog.get("400"), oplog.get("422"), system.get("403"))) {
			assertThat(r.at("/content/application~1problem+json/schema/$ref").asString())
				.isEqualTo("#/components/schemas/ProblemDetail");
		}
		// 재인증(1-B2 스펙 3.3·7절)
		JsonNode reauth = written.at("/paths/~1api~1admin~1reauth/post/responses");
		assertThat(fieldNames(reauth)).containsExactlyInAnyOrder("204", "401", "403", "422", "429");
		assertThat(reauth.at("/204/content").isMissingNode()).isTrue();
		for (String code : java.util.List.of("401", "403", "422", "429")) {
			assertThat(reauth.at("/" + code + "/content/application~1problem+json/schema/$ref").asString())
				.as(code).isEqualTo("#/components/schemas/ProblemDetail");
		}
		// A-402 상세·비활성·활성(1-B2 스펙 7절). 409 는 마지막 관리자 잠금(Task 6)
		JsonNode detail = written.at("/paths/~1api~1admin~1users~1{id}/get/responses");
		assertThat(fieldNames(detail)).containsExactlyInAnyOrder("200", "401", "403", "404", "429");
		JsonNode deactivate = written.at("/paths/~1api~1admin~1users~1{id}~1deactivate/post/responses");
		assertThat(fieldNames(deactivate)).containsExactlyInAnyOrder("204", "401", "403", "404", "409", "429");
		JsonNode activate = written.at("/paths/~1api~1admin~1users~1{id}~1activate/post/responses");
		assertThat(fieldNames(activate)).containsExactlyInAnyOrder("204", "401", "403", "404", "429");
		for (JsonNode r : java.util.List.of(deactivate, activate)) {
			assertThat(r.at("/204/content").isMissingNode()).isTrue();
			for (String code : java.util.List.of("401", "403", "404", "429")) {
				assertThat(r.at("/" + code + "/content/application~1problem+json/schema/$ref").asString())
					.as(code).isEqualTo("#/components/schemas/ProblemDetail");
			}
		}
		assertThat(detail.at("/404/content/application~1problem+json/schema/$ref").asString())
			.isEqualTo("#/components/schemas/ProblemDetail");
		assertThat(fieldNames(written.at("/components/schemas/UserDetail/properties"))).containsExactlyInAnyOrder("id",
				"email", "display_name", "is_active", "created_at", "roles", "assignable_roles");
		assertThat(fieldNames(written.at("/components/schemas/RoleRef/properties"))).containsExactlyInAnyOrder("id",
				"key", "name", "is_superuser");
		// A-401 목록·A-402 생성(1-B2 스펙 7절). 생성 본문은 고정 레코드 — 역할·활성 상태·기준 시각 칸이 없다
		JsonNode list = written.at("/paths/~1api~1admin~1users/get/responses");
		assertThat(fieldNames(list)).containsExactlyInAnyOrder("200", "400", "401", "403", "422", "429");
		JsonNode create = written.at("/paths/~1api~1admin~1users/post/responses");
		assertThat(fieldNames(create)).containsExactlyInAnyOrder("201", "400", "401", "403", "409", "422", "429");
		for (JsonNode r : java.util.List.of(list.get("400"), list.get("422"), create.get("409"), create.get("422"))) {
			assertThat(r.at("/content/application~1problem+json/schema/$ref").asString())
				.isEqualTo("#/components/schemas/ProblemDetail");
		}
		assertThat(fieldNames(written.at("/components/schemas/UserPage/properties"))).containsExactlyInAnyOrder("items",
				"page", "page_size", "has_next");
		assertThat(fieldNames(written.at("/components/schemas/UserItem/properties"))).containsExactlyInAnyOrder("id",
				"email", "display_name", "is_active", "roles", "created_at");
		assertThat(fieldNames(written.at("/components/schemas/CreateUser/properties"))).containsExactlyInAnyOrder("email",
				"display_name", "password");
		assertThat(fieldNames(written.at("/components/schemas/Created/properties"))).containsExactly("id");
		JsonNode problem = written.at("/components/schemas/ProblemDetail/properties");
		assertThat(fieldNames(problem)).contains("detail", "errors").doesNotContain("properties");
		assertThat(problem.at("/errors/additionalProperties/type").asString()).isEqualTo("string");
	}

	static void merge(ObjectNode into, JsonNode from) {
		if (from == null) {
			return;
		}
		for (Iterator<Map.Entry<String, JsonNode>> it = from.properties().iterator(); it.hasNext();) {
			Map.Entry<String, JsonNode> e = it.next();
			into.set(e.getKey(), e.getValue());
		}
	}

	/**
	 * 앱은 spring.jackson.property-naming-strategy=SNAKE_CASE 로 모든 JSON 이름을 바꾸지만 springdoc 은 그 설정을
	 * 따르지 않는다(Jackson 3, 2026-11-13 확인). 같은 규칙을 스키마 속성 이름과 required 에 적용한다.
	 */
	static void snakeCase(JsonNode schemas) {
		schemas.properties().forEach(s -> {
			ObjectNode schema = (ObjectNode) s.getValue();
			if (schema.get("properties") instanceof ObjectNode props) {
				ObjectNode renamed = props.objectNode();
				props.properties().forEach(p -> renamed.set(snake(p.getKey()), p.getValue()));
				schema.set("properties", renamed);
			}
			if (schema.get("required") instanceof tools.jackson.databind.node.ArrayNode req) {
				tools.jackson.databind.node.ArrayNode renamed = req.arrayNode();
				req.forEach(r -> renamed.add(snake(r.asString())));
				schema.set("required", renamed);
			}
		});
	}

	/**
	 * Spring 은 ProblemDetail.properties 를 응답 최상위로 펼친다(Jackson mixin 의 @JsonAnyGetter). springdoc 은 이를 모르고
	 * properties 칸을 문서에 적으므로, 우리가 쓰는 확장 멤버(ApiErrors 의 errors: 칸 → 메시지)로 바꾼다.
	 */
	static void problemMembers(JsonNode schema) {
		ObjectNode props = (ObjectNode) schema.get("properties");
		props.remove("properties");
		props.putObject("errors").put("type", "object").putObject("additionalProperties").put("type", "string");
	}

	static String snake(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
	}

	/** 키를 정렬해 실행마다 같은 파일이 나오게 한다. */
	Object sorted(JsonNode n) {
		return json.convertValue(n, TreeMap.class);
	}

	static java.util.List<String> fieldNames(JsonNode n) {
		java.util.List<String> out = new java.util.ArrayList<>();
		n.properties().forEach(e -> out.add(e.getKey()));
		return out;
	}
}
