package dev.ondolithj.shared;

import static dev.ondolithj.auth.AuthFixture.PASSWORD;
import static dev.ondolithj.auth.AuthFixture.loginBody;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.Actor;
import dev.ondolithj.auth.AuthFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 작업 로그 기록기와 로그인 성공 기록(1-B1 스펙 4.1·4.4). operation_logs 는 비울 수 없으니 시험마다 새 계정으로 센다. */
@ExtendWith(OutputCaptureExtension.class)
class OperationLogTest {

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

	static String email() {
		return "oplog" + SEQ.incrementAndGet() + "@ondolith.test";
	}

	/** 조건에 맞는 행 수. */
	static long count(String where, Object param) throws SQLException {
		try (Connection c = f.db();
				PreparedStatement s = c.prepareStatement("SELECT count(*) FROM operation_logs WHERE " + where)) {
			s.setObject(1, param);
			try (ResultSet r = s.executeQuery()) {
				r.next();
				return r.getLong(1);
			}
		}
	}

	@Test
	void 로그인_성공은_그_계정의_auth_login_행_하나를_남긴다() throws Exception {
		String email = email();
		UUID id = f.user(email);
		f.loginAs(email);
		try (Connection c = f.db();
				PreparedStatement s = c.prepareStatement(
						"SELECT action, actor_email, target_type, target_id, summary, host(ip) FROM operation_logs WHERE actor_user_id = ?")) {
			s.setObject(1, id);
			try (ResultSet r = s.executeQuery()) {
				assertThat(r.next()).isTrue();
				assertThat(r.getString(1)).isEqualTo("auth.login");
				assertThat(r.getString(2)).isEqualTo(email);
				assertThat(r.getString(3)).isEqualTo("user");
				assertThat(r.getString(4)).isEqualTo(id.toString());
				assertThat(r.getString(5)).isEmpty();
				assertThat(r.getString(6)).isEqualTo("127.0.0.1");
				assertThat(r.next()).isFalse();
			}
		}
	}

	@Test
	void 로그인_실패는_행을_남기지_않는다() throws Exception {
		String email = email();
		UUID id = f.user(email);
		TestHttp http = f.http();
		assertThat(http.post("/api/auth/login", loginBody(email, "wrong-pass-0000")).statusCode()).isEqualTo(401);
		assertThat(count("actor_user_id = ?", id)).isZero();
		assertThat(count("actor_email = ?", email)).isZero();
		assertThat(count("target_id = ?", id.toString())).isZero();
	}

	@Test
	void inet_으로_읽을_수_없는_IP_도_행은_남고_ip_는_NULL() throws Exception {
		String email = email();
		UUID id = f.user(email);
		String target = UUID.randomUUID().toString();
		f.ctx.getBean(OperationLog.class)
			.record(new Actor(id, email, "", Instant.now(), Set.of()), OperationLog.Action.AUTH_LOGIN, target, "",
					"fe80::1%eth0");
		assertThat(count("target_id = ?", target)).isEqualTo(1);
		assertThat(count("ip IS NULL AND target_id = ?", target)).isEqualTo(1);
	}

	@Test
	void 대상_id_와_IP_가_null_이어도_행이_남는다() throws Exception {
		String email = email();
		UUID id = f.user(email);
		f.ctx.getBean(OperationLog.class)
			.record(new Actor(id, email, "", Instant.now(), Set.of()), OperationLog.Action.SETTINGS_UPDATE, null,
					"site.name", null);
		assertThat(count("action = 'settings.update' AND target_id IS NULL AND ip IS NULL AND actor_user_id = ?", id))
			.isEqualTo(1);
	}

	@Test
	void 기록이_실패해도_로그인은_204_이고_ERROR_줄에_이메일과_IP_가_없다(CapturedOutput out) throws Exception {
		String email = email();
		f.user(email, "admin");
		// 행 값이 메시지에 들어간다 — PostgreSQL 제약 위반의 「Failing row contains (…)」와 같은 위험을 재현한다
		f.sql("""
				CREATE FUNCTION oplog_reject() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN RAISE EXCEPTION 'rejected: %', row_to_json(NEW); END $$""");
		f.sql("CREATE TRIGGER oplog_reject BEFORE INSERT ON operation_logs FOR EACH ROW EXECUTE FUNCTION oplog_reject()");
		try {
			TestHttp http = f.http();
			assertThat(http.post("/api/auth/login", loginBody(email, PASSWORD)).statusCode()).isEqualTo(204);
			assertThat(http.get("/api/admin").statusCode()).isEqualTo(200);
		} finally {
			f.sql("DROP TRIGGER oplog_reject ON operation_logs");
			f.sql("DROP FUNCTION oplog_reject()");
		}
		// 예외 메시지는 여러 줄(SQL 본문)이라 행 값이 다음 줄로 밀린다 — 이메일·rejected 는 출력 전체에서 본다
		assertThat(out.getAll()).doesNotContain(email).doesNotContain("rejected");
		// 127.0.0.1 은 다른 줄(기동 로그 등)에도 나오므로 기록 실패 줄만 본다
		List<String> lines = out.getAll().lines().filter(l -> l.contains("작업 로그를 남기지 못했습니다")).toList();
		assertThat(lines).isNotEmpty();
		assertThat(lines).allSatisfy(l -> assertThat(l).contains("auth.login").doesNotContain(email)
			.doesNotContain("127.0.0.1").doesNotContain("rejected"));
	}
}
