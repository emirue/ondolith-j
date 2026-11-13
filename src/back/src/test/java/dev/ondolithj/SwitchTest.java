package dev.ondolithj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ondolithj.config.ConfigFile;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class SwitchTest {

	static final String DB_PASS = "pg-pass-switch";

	static final AtomicInteger SEQ = new AtomicInteger();

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine");

	@TempDir
	Path dir;

	@BeforeAll
	static void role() throws SQLException {
		admin("CREATE ROLE app LOGIN PASSWORD '" + DB_PASS + "'");
	}

	@AfterEach
	void stop() {
		var c = Launcher.current();
		if (c != null) {
			c.close();
		}
		Launcher.exitHandler = System::exit;
	}

	static void admin(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
			c.createStatement().execute(sql);
		}
	}

	String newDb() throws SQLException {
		String db = "s" + SEQ.incrementAndGet();
		admin("CREATE DATABASE " + db + " OWNER app");
		return db;
	}

	String jdbc(String db) {
		return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db;
	}

	/** 루프백에서 빈 포트. 와일드카드로 고르면 다른 프로세스가 127.0.0.1 에 쥔 포트를 고를 수 있다(SO_REUSEADDR). */
	static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return s.getLocalPort();
		}
	}

	String body(String db) {
		return JsonMapper.builder().build().writeValueAsString(Map.ofEntries(Map.entry("db_host", pg.getHost()),
				Map.entry("db_port", pg.getMappedPort(5432)), Map.entry("db_user", "app"),
				Map.entry("db_password", DB_PASS), Map.entry("db_name", db), Map.entry("db_sslmode", "disable"),
				Map.entry("site_name", "전환 사이트"), Map.entry("site_type", "shop"),
				Map.entry("site_url", "http://site.ondolith.test"), Map.entry("admin_email", "admin@ondolith.test"),
				Map.entry("admin_password", "admin-pass-switch"), Map.entry("admin_password_confirm", "admin-pass-switch")));
	}

	/** 1초 간격으로 health 가 running 이 될 때까지. 연결 실패는 전환 중이라 다시 본다. */
	static boolean waitRunning(TestHttp http, int seconds) throws InterruptedException {
		for (int i = 0; i < seconds; i++) {
			try {
				if (http.get("/api/health").body().contains("\"state\":\"running\"")) {
					return true;
				}
			} catch (IOException e) {
				// 전환 중
			}
			Thread.sleep(1000);
		}
		return false;
	}

	Path install(int port, String db) throws Exception {
		Path config = dir.resolve("ondolith-j.properties");
		Launcher.startInstall(config, "--server.port=" + port, "--server.address=127.0.0.1");
		TestHttp http = new TestHttp("http://127.0.0.1:" + port);
		assertThat(http.post("/api/install", body(db)).statusCode()).isEqualTo(202);
		assertThat(waitRunning(http, 30)).isTrue();
		return config;
	}

	@Test
	void 설치_뒤_같은_포트에서_운영으로_바뀐다() throws Exception {
		int port = freePort();
		install(port, newDb());
		TestHttp http = new TestHttp("http://127.0.0.1:" + port);
		assertThat(http.post("/api/install", "{}").statusCode()).isEqualTo(404);
		HttpResponse<String> site = http.get("/api/site");
		assertThat(site.statusCode()).isEqualTo(200);
		assertThat(site.body()).contains("\"name\":\"전환 사이트\"").contains("\"type\":\"shop\"");
	}

	@Test
	void 설정_파일이_있으면_운영으로_뜬다() throws Exception {
		Path config = install(freePort(), newDb());
		Launcher.current().close();
		assertThat(Launcher.run(new String[] { "--config=" + config, "--server.port=0" })).isZero();
		TestHttp http = TestHttp.of(Launcher.current());
		assertThat(http.get("/api/site").body()).contains("\"name\":\"전환 사이트\"");
		assertThat(http.get("/api/health").body()).contains("\"state\":\"running\"");
	}

	@Test
	void 운영_컨텍스트가_못_뜨면_종료_1() throws Exception {
		AtomicInteger code = new AtomicInteger(-1);
		Launcher.exitHandler = code::set;
		ConfigFile bad = new ConfigFile("jdbc:postgresql://127.0.0.1:" + freePort() + "/none", "app", "x",
				URI.create("http://site.ondolith.test"), false, ConfigFile.newSealKey(), dir.resolve("u"), dir.resolve("b"),
				Instant.now());
		var installCtx = Launcher.startInstall(dir.resolve("ondolith-j.properties"), "--server.port=0");
		Launcher.switchToRunning(installCtx, bad, new String[] { "--server.port=0",
				"--spring.datasource.hikari.initialization-fail-timeout=1" });
		assertThat(code.get()).isEqualTo(1);
		assertThat(installCtx.isActive()).isFalse();
	}

	@Test
	void 마이그레이션이_실패하면_운영으로_뜨지_않는다() throws Exception {
		String db = newDb();
		ConfigFile config = new ConfigFile(jdbc(db), "app", DB_PASS, URI.create("http://site.ondolith.test"), false,
				ConfigFile.newSealKey(), dir.resolve("u"), dir.resolve("b"), Instant.now());
		assertThatThrownBy(() -> Launcher.startRunning(config, "--server.port=0",
				"--spring.flyway.locations=classpath:db/migration,classpath:db/failing"))
			// 접속 실패 같은 다른 이유로 못 뜬 것이 아님을 확인한다
			.hasStackTraceContaining("V9999__fails.sql")
			.hasStackTraceContaining("division by zero");
		try (Connection c = DriverManager.getConnection(jdbc(db), "app", DB_PASS);
				ResultSet r = c.createStatement().executeQuery("SELECT to_regclass('public.half_done')::text")) {
			r.next();
			assertThat(r.getString(1)).isNull();
		}
	}
}
