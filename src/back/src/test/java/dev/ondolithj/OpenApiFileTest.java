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
		Files.writeString(OUT, json.writeValueAsString(sorted(merged)) + "\n");

		JsonNode written = json.readTree(Files.readString(OUT));
		assertThat(fieldNames(written.get("paths"))).containsExactly("/api/health", "/api/install", "/api/site");
		assertThat(fieldNames(written.at("/components/schemas/InstallRequest/properties"))).contains("db_host",
				"admin_password_confirm");
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
