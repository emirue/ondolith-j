package dev.ondolithj.install;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.Launcher;
import dev.ondolithj.TestHttp;
import dev.ondolithj.config.ConfigFile;
import dev.ondolithj.config.ConfigLoad;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class InstallApiTest {

	/** 로그·응답에서 찾아볼 수 있게 눈에 띄는 가짜 값. */
	static final String DB_PASS = "pg-pass-7f3a";

	static final String ADMIN_PASS = "admin-pass-9c1d";

	static final AtomicInteger SEQ = new AtomicInteger();

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18-alpine");

	@TempDir
	Path dir;

	String db;

	ConfigurableApplicationContext ctx;

	TestHttp http;

	@BeforeAll
	static void role() throws SQLException {
		admin("CREATE ROLE app LOGIN PASSWORD '" + DB_PASS + "'");
	}

	@BeforeEach
	void start() throws SQLException {
		db = "t" + SEQ.incrementAndGet();
		admin("CREATE DATABASE " + db + " OWNER app");
		ctx = Launcher.startInstall(config(), cfg -> {
		}, "--server.port=0");
		http = TestHttp.of(ctx);
	}

	@AfterEach
	void stop() {
		ctx.close();
	}

	Path config() {
		return dir.resolve("conf/ondolith-j.properties");
	}

	static void admin(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
			c.createStatement().execute(sql);
		}
	}

	String jdbc() {
		return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db;
	}

	List<String> rows(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(jdbc(), "app", DB_PASS);
				ResultSet r = c.createStatement().executeQuery(sql)) {
			List<String> out = new ArrayList<>();
			while (r.next()) {
				out.add(r.getString(1));
			}
			return out;
		}
	}

	Map<String, Object> valid() {
		Map<String, Object> m = new HashMap<>();
		m.put("db_host", pg.getHost());
		m.put("db_port", pg.getMappedPort(5432));
		m.put("db_user", "app");
		m.put("db_password", DB_PASS);
		m.put("db_name", db);
		m.put("db_sslmode", "disable");
		m.put("site_name", "시험 사이트");
		m.put("site_type", "cms");
		m.put("site_url", "https://site.ondolith.test");
		m.put("admin_email", "Admin@Ondolith.test");
		m.put("admin_password", ADMIN_PASS);
		m.put("admin_password_confirm", ADMIN_PASS);
		return m;
	}

	HttpResponse<String> install(Map<String, Object> body) throws Exception {
		return http.post("/api/install", JsonMapper.builder().build().writeValueAsString(body));
	}

	Map<String, Object> with(Object... kv) {
		Map<String, Object> m = valid();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	Path confDir() throws IOException {
		return Files.createDirectories(config().getParent());
	}

	Set<String> filesIn(Path d) throws IOException {
		try (Stream<Path> s = Files.list(d)) {
			return s.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
		}
	}

	void readOnly(Path d, boolean ro) throws IOException {
		Files.setPosixFilePermissions(d, PosixFilePermissions.fromString(ro ? "r-x------" : "rwx------"));
	}

	@Test
	void 정상_설치는_202_와_설정_파일_0600() throws Exception {
		confDir();
		assertThat(install(valid()).statusCode()).isEqualTo(202);
		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(config()))).isEqualTo("rw-------");
		ConfigLoad loaded = ConfigFile.load(config());
		assertThat(loaded).isInstanceOf(ConfigLoad.Loaded.class);
		ConfigFile c = ((ConfigLoad.Loaded) loaded).config();
		assertThat(c.dbPassword()).isEqualTo(DB_PASS);
		assertThat(c.secureCookies()).isTrue();
		assertThat(rows("SELECT email FROM users")).containsExactly("admin@ondolith.test");
		assertThat(rows("SELECT password_hash FROM users")).singleElement().asString().startsWith("$2");
		assertThat(rows("SELECT r.key FROM user_roles ur JOIN roles r ON r.id = ur.role_id")).containsExactly("admin");
		assertThat(rows("SELECT key || '=' || value FROM settings ORDER BY key")).containsExactly("site.name=시험 사이트",
				"site.type=cms");
		assertThat(filesIn(config().getParent())).containsExactly("ondolith-j.properties");
	}

	@Test
	void 검증_실패는_422_와_칸별_오류() throws Exception {
		HttpResponse<String> r = install(with("db_port", 0, "admin_password", "short", "admin_password_confirm",
				"short", "site_type", "blog", "db_host", "localhost/x?socketFactory=a", "site_url", "ftp://x"));
		assertThat(r.statusCode()).isEqualTo(422);
		assertThat(r.body()).contains("\"db_port\"").contains("\"admin_password\"").contains("\"site_type\"")
			.contains("\"db_host\"").contains("\"site_url\"").doesNotContain(ADMIN_PASS);
	}

	@Test
	void 비밀번호_확인이_다르면_422() throws Exception {
		HttpResponse<String> r = install(with("admin_password_confirm", "different-pass-0"));
		assertThat(r.statusCode()).isEqualTo(422);
		assertThat(r.body()).contains("\"admin_password_confirm\"");
	}

	@Test
	void 바이트_72를_넘는_비밀번호는_422() throws Exception {
		String p = "가".repeat(25); // UTF-8 75바이트. bcrypt 는 72바이트 뒤를 버린다
		HttpResponse<String> r = install(with("admin_password", p, "admin_password_confirm", p));
		assertThat(r.statusCode()).isEqualTo(422);
		assertThat(r.body()).contains("\"admin_password\"");
	}

	@Test
	void 설정_파일_자리에_못_쓰면_500_이고_DB_는_그대로() throws Exception {
		Path d = confDir();
		readOnly(d, true);
		try {
			HttpResponse<String> r = install(valid());
			assertThat(r.statusCode()).isEqualTo(500);
			assertThat(r.body()).contains(d.toString());
		} finally {
			readOnly(d, false);
		}
		assertThat(rows("SELECT to_regclass('public.flyway_schema_history')::text")).containsOnlyNulls();
	}

	@Test
	void DB_접속_실패는_400_원문_비밀번호_없음() throws Exception {
		confDir();
		HttpResponse<String> r = install(with("db_password", "wrong-password-123"));
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).contains("password authentication failed").doesNotContain("wrong-password-123");
		assertThat(filesIn(config().getParent())).isEmpty();
	}

	@Test
	void DB_이름으로_JDBC_옵션을_주입할_수_없다() throws Exception {
		confDir();
		HttpResponse<String> r = install(with("db_name", db + "?socketFactory=java.lang.String"));
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).contains("does not exist");
	}

	@Test
	void 이력_없는_비어있지_않은_스키마는_사용중() throws Exception {
		confDir();
		try (Connection c = DriverManager.getConnection(jdbc(), "app", DB_PASS)) {
			c.createStatement().execute("CREATE TABLE x (id int)");
		}
		HttpResponse<String> r = install(valid());
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).contains("이미 사용 중인 데이터베이스입니다");
	}

	@Test
	void 같은_이메일_계정이_있으면_사용중() throws Exception {
		confDir();
		assertThat(install(valid()).statusCode()).isEqualTo(202);
		ctx.close();
		Path other = dir.resolve("other/ondolith-j.properties");
		Files.createDirectories(other.getParent());
		ctx = Launcher.startInstall(other, cfg -> {
		}, "--server.port=0");
		http = TestHttp.of(ctx);
		HttpResponse<String> r = install(valid());
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).contains("이미 사용 중인 데이터베이스입니다");
	}

	@Test
	void 동시_요청은_하나만() throws Exception {
		confDir();
		try (ServerSocket silent = new ServerSocket(0)) {
			List<Socket> held = new ArrayList<>();
			Thread acceptor = Thread.ofVirtual().start(() -> {
				try {
					held.add(silent.accept()); // 받기만 하고 응답하지 않는다
				} catch (IOException e) {
					// 닫힘
				}
			});
			http.get("/api/health");
			CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
				try {
					return install(with("db_host", "127.0.0.1", "db_port", silent.getLocalPort())).statusCode();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			Thread.sleep(500);
			int second = install(valid()).statusCode();
			assertThat(second).isEqualTo(409);
			assertThat(first.get()).isEqualTo(400);
			acceptor.interrupt();
		}
	}

	@Test
	void 설치_뒤_재요청은_404() throws Exception {
		confDir();
		assertThat(install(valid()).statusCode()).isEqualTo(202);
		assertThat(install(valid()).statusCode()).isEqualTo(404);
	}

	@Test
	void 실패_뒤_재시도는_끝난다() throws Exception {
		Path d = confDir();
		readOnly(d, true);
		try {
			assertThat(install(valid()).statusCode()).isEqualTo(500);
		} finally {
			readOnly(d, false);
		}
		assertThat(install(valid()).statusCode()).isEqualTo(202);
	}

	@Test
	void 마이그레이션은_됐고_관리자가_없는_DB_는_이어서_설치() throws Exception {
		confDir();
		Flyway.configure().dataSource(jdbc(), "app", DB_PASS).locations("classpath:db/migration").load().migrate();
		assertThat(install(valid()).statusCode()).isEqualTo(202);
	}

	@Test
	void 설정_파일_교체가_실패하면_관리자를_지우고_재시도_가능() throws Exception {
		Files.createDirectories(config());
		Files.writeString(config().resolve("keep"), "x");
		assertThat(install(valid()).statusCode()).isEqualTo(500);
		assertThat(rows("SELECT count(*) FROM users")).containsExactly("0");
		assertThat(rows("SELECT count(*) FROM settings WHERE key LIKE 'site.%'")).containsExactly("0");
		Files.delete(config().resolve("keep"));
		Files.delete(config());
		assertThat(install(valid()).statusCode()).isEqualTo(202);
		assertThat(filesIn(config().getParent())).containsExactly("ondolith-j.properties");
	}

	@Test
	void 로그에_비밀번호가_없다(CapturedOutput out) throws Exception {
		confDir();
		install(with("db_password", DB_PASS + "x"));
		assertThat(install(valid()).statusCode()).isEqualTo(202);
		assertThat(out.getAll()).doesNotContain(DB_PASS).doesNotContain(ADMIN_PASS);
	}

	@Test
	void 이상한_사이트_주소는_422_이고_DB_는_그대로() throws Exception {
		confDir();
		for (String bad : new String[] { "https://example.com/a|b", "https://한국.kr", "http://under_score.example" }) {
			HttpResponse<String> r = install(with("site_url", bad));
			assertThat(r.statusCode()).as(bad).isEqualTo(422);
			assertThat(r.body()).as(bad).contains("\"site_url\"");
		}
		assertThat(rows("SELECT to_regclass('public.flyway_schema_history')::text")).containsOnlyNulls();
	}

	@Test
	void 특이한_DB_이름도_설치한_설정으로_운영_컨텍스트가_뜨고_쿠키_설정이_들어간다() throws Exception {
		confDir();
		String odd = "my db %한";
		admin("CREATE DATABASE \"" + odd + "\" OWNER app");
		assertThat(install(with("db_name", odd)).statusCode()).isEqualTo(202);
		ConfigFile c = ((ConfigLoad.Loaded) ConfigFile.load(config())).config();
		try (var running = Launcher.startRunning(c, "--server.port=0")) {
			HttpResponse<String> site = TestHttp.of(running).get("/api/site");
			assertThat(site.statusCode()).isEqualTo(200);
			assertThat(site.body()).contains("시험 사이트");
			var env = running.getEnvironment();
			assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
			assertThat(env.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
			assertThat(env.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
		}
	}

	@Test
	void 다른_이메일이라도_사용자가_있으면_사용중() throws Exception {
		confDir();
		assertThat(install(valid()).statusCode()).isEqualTo(202);
		ctx.close();
		Path other = dir.resolve("other/ondolith-j.properties");
		Files.createDirectories(other.getParent());
		ctx = Launcher.startInstall(other, cfg -> {
		}, "--server.port=0");
		http = TestHttp.of(ctx);
		HttpResponse<String> r = install(with("admin_email", "someone@ondolith.test"));
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).contains("이미 사용 중인 데이터베이스입니다");
		assertThat(rows("SELECT value FROM settings WHERE key = 'site.name'")).containsExactly("시험 사이트");
	}

	@Test
	void 권한이_없는_다른_역할의_표도_사용중으로_본다() throws Exception {
		confDir();
		try (Connection c = DriverManager.getConnection(jdbc(), pg.getUsername(), pg.getPassword())) {
			c.createStatement().execute("CREATE TABLE secret (id int)");
			c.createStatement().execute("REVOKE ALL ON secret FROM PUBLIC");
		}
		HttpResponse<String> r = install(valid());
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).contains("이미 사용 중인 데이터베이스입니다");
	}

	@Test
	void 호스트_이름의_밑줄은_검증을_통과한다() throws Exception {
		confDir();
		HttpResponse<String> r = install(with("db_host", "my_db"));
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).doesNotContain("\"db_host\"");
	}

	@Test
	void DEBUG_로그에도_비밀번호가_없다(CapturedOutput out) throws Exception {
		ctx.close();
		ctx = Launcher.startInstall(config(), cfg -> {
		}, "--server.port=0", "--logging.level.org.springframework.web=DEBUG");
		http = TestHttp.of(ctx);
		confDir();
		install(with("admin_password", "short-pw", "admin_password_confirm", "short-pw"));
		install(with("site_type", "blog"));
		assertThat(out.getAll()).contains("POST \"/api/install\"").doesNotContain(DB_PASS).doesNotContain("short-pw")
			.doesNotContain(ADMIN_PASS);
	}
}
