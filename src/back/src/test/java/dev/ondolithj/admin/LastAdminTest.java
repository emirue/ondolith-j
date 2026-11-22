package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ondolithj.TestHttp;
import dev.ondolithj.auth.ActorQuery;
import dev.ondolithj.auth.AuthFixture;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 마지막 관리자 잠금(1-B2 스펙 5절, 12절). 함수를 직접 부르는 시험과 동시 요청 시험. 시험마다 모든 계정을 비활성으로 돌려
 * 앞 시험의 보유자를 셈에서 뺀다. 모든 대기에 상한이 있다 — 잠금이 잘못되면 멈추지 않고 실패한다.
 */
class LastAdminTest {

	static final String LAST_ADMIN = "마지막 관리자는 비활성화·삭제할 수 없습니다";

	static final AtomicInteger SEQ = new AtomicInteger();

	static AuthFixture f;

	static LastAdmin lastAdmin;

	static JdbcClient app;

	@BeforeAll
	static void start() throws Exception {
		f = new AuthFixture().start(false, "--spring.datasource.hikari.connection-init-sql=SET lock_timeout = '20s'");
		lastAdmin = f.ctx.getBean(LastAdmin.class);
		app = f.ctx.getBean(JdbcClient.class);
		// 앱 연결의 대기 상한이 실제로 걸렸다(속성 바인딩 확인)
		assertThat(app.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("20s");
		// superuser 가 아니면서 비활성화를 부를 수 있는 시험 역할(R6 을 지나려면 대상이 보유자가 아니어야 한다)
		f.sql("INSERT INTO roles (key, name) VALUES ('probe_users', '시험')");
		f.sql("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r, permissions p "
				+ "WHERE r.key = 'probe_users' AND p.key IN ('admin.access', 'user.update')");
	}

	@AfterAll
	static void stop() {
		f.close();
	}

	@BeforeEach
	void 모두_비활성() throws SQLException {
		f.sql("UPDATE users SET is_active = false");
	}

	static UUID user(String... roles) throws SQLException {
		return f.user("last" + SEQ.incrementAndGet() + "@ondolith.test", roles);
	}

	record Login(UUID id, TestHttp http) {
	}

	static Login login(String... roles) throws Exception {
		String email = "last" + SEQ.incrementAndGet() + "@ondolith.test";
		UUID id = f.user(email, roles);
		return new Login(id, f.loginAs(email));
	}

	static IntSupplier deactivate(UUID id) {
		return () -> app.sql("UPDATE users SET is_active = false WHERE id = ?").param(id).update();
	}

	static boolean active(UUID id) throws SQLException {
		return one("SELECT is_active::int FROM users WHERE id = ?", id) == 1;
	}

	static long holders() throws SQLException {
		return one("SELECT count(*) FROM users u WHERE u.is_active AND " + ActorQuery.HOLDS_SUPERUSER);
	}

	static long one(String sql, Object... params) throws SQLException {
		try (Connection c = f.db(); PreparedStatement s = c.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				s.setObject(i + 1, params[i]);
			}
			try (ResultSet r = s.executeQuery()) {
				r.next();
				return r.getLong(1);
			}
		}
	}

	static Supplier<CompletableFuture<HttpResponse<String>>> deactivating(TestHttp http, UUID target) {
		return () -> {
			try {
				return http.postAsync("/api/admin/users/" + target + "/deactivate", "", Duration.ofSeconds(30));
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};
	}

	/** 시험 연결이 잡는 잠금. */
	interface Lock {
		void take(Connection c) throws SQLException;
	}

	/**
	 * 시험 연결이 각 요청이 쓸 행을 먼저 잡고, 요청을 모두 보낸 뒤 앱 연결 둘이 잠금을 기다리는 것을 보고 푼다. 상태 코드를 보낸
	 * 순서대로 돌려준다. Task 7 의 교차 시험이 다시 쓴다.
	 */
	static List<Integer> race(Lock lock, List<Supplier<CompletableFuture<HttpResponse<String>>>> sends) throws Exception {
		List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
		Connection c = f.db();
		try {
			c.setAutoCommit(false);
			try (Statement s = c.createStatement()) {
				s.execute("SET lock_timeout = '5s'");
				s.execute("SET statement_timeout = '30s'");
			}
			lock.take(c);
			sends.forEach(s -> sent.add(s.get()));
			awaitWaiters(2);
		}
		finally {
			c.rollback();
			c.close();
		}
		List<Integer> codes = new ArrayList<>();
		for (CompletableFuture<HttpResponse<String>> r : sent) {
			codes.add(r.get(30, TimeUnit.SECONDS).statusCode());
		}
		return codes;
	}

	/** 잠금을 기다리는 연결이 n 개 이상이 될 때까지 100ms 마다 본다. 10초를 넘기면 실패. */
	static void awaitWaiters(int n) throws Exception {
		try (Connection o = f.db(); Statement s = o.createStatement()) {
			s.execute("SET statement_timeout = '5s'");
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (true) {
				try (ResultSet r = s.executeQuery("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted")) {
					r.next();
					if (r.getLong(1) >= n) {
						return;
					}
				}
				if (System.nanoTime() > deadline) {
					throw new AssertionError("앱 연결 " + n + "개가 10초 안에 잠금을 기다리지 않았다");
				}
				Thread.sleep(100);
			}
		}
	}

	@Test
	void 유일한_활성_보유자는_거절되고_쓰지_않는다() throws Exception {
		UUID a = user("admin");
		assertThat(lastAdmin.run(a, deactivate(a))).isEqualTo(LastAdmin.Outcome.REFUSED);
		assertThat(active(a)).isTrue();
	}

	@Test
	void 비활성_보유자의_회수와_이미_비활성인_대상은_통과한다() throws Exception {
		user("admin");
		UUID b = user("admin");
		f.sql("UPDATE users SET is_active = false WHERE id = ?", b);
		assertThat(lastAdmin.run(b, deactivate(b))).isEqualTo(LastAdmin.Outcome.DONE);
		assertThat(lastAdmin.run(b, () -> app.sql("DELETE FROM user_roles WHERE user_id = ?").param(b).update()))
			.isEqualTo(LastAdmin.Outcome.DONE);
		assertThat(one("SELECT count(*) FROM user_roles WHERE user_id = ?", b)).isZero();
	}

	@Test
	void 함수를_부르기_직전에_보유자가_된_대상도_잠금_아래서_다시_읽어_거절한다() throws Exception {
		UUID x = user();
		f.sql("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE key = 'admin'", x);
		assertThat(lastAdmin.run(x, deactivate(x))).isEqualTo(LastAdmin.Outcome.REFUSED);
		assertThat(active(x)).isTrue();
	}

	@Test
	void member_에_superuser_를_켜도_셈이_늘지_않는다() throws Exception {
		UUID a = user("admin");
		UUID m = user();
		try {
			f.sql("DROP INDEX roles_one_superuser_idx");
			f.sql("UPDATE roles SET is_superuser = true WHERE key = 'member'");
			f.sql("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE key = 'member'", m);
			assertThat(lastAdmin.run(a, deactivate(a))).isEqualTo(LastAdmin.Outcome.REFUSED);
			assertThat(active(a)).isTrue();
		}
		finally {
			f.sql("DELETE FROM user_roles WHERE user_id = ?", m);
			f.sql("UPDATE roles SET is_superuser = false WHERE key = 'member'");
			f.sql("CREATE UNIQUE INDEX roles_one_superuser_idx ON roles ((is_superuser)) WHERE is_superuser");
		}
	}

	@Test
	void 없는_대상과_잠금_아래서_쓰기가_0행이면_GONE() throws Exception {
		assertThat(lastAdmin.run(UUID.randomUUID(), () -> 1)).isEqualTo(LastAdmin.Outcome.GONE);
		UUID x = user();
		assertThat(lastAdmin.run(x, () -> 0)).isEqualTo(LastAdmin.Outcome.GONE);
	}

	@Test
	@Timeout(90)
	void 두_관리자가_서로를_동시에_비활성화하면_하나만_된다() throws Exception {
		Login a = login("admin");
		Login b = login("admin");
		List<Integer> codes = race(c -> {
			try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM users WHERE id IN (?, ?) FOR UPDATE")) {
				s.setObject(1, a.id());
				s.setObject(2, b.id());
				s.executeQuery().close();
			}
		}, List.of(deactivating(a.http(), b.id()), deactivating(b.http(), a.id())));
		codes.sort(null);
		assertThat(codes).isEqualTo(List.of(204, 409));
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id IN (?, ?) AND action = 'user.denied' "
				+ "AND summary = 'user.deactivate last_admin'", a.id(), b.id())).isEqualTo(1);
		assertThat(holders()).isEqualTo(1);
	}

	/**
	 * 처리기가 대상을 읽은 뒤(보유자 아님, R6 통과)에 대상이 보유자가 된다. 처리기가 먼저 읽은 값으로 잠금을 건너뛰면 204 다(스펙 5절
	 * 「늘 이 함수를 거친다」). 시험 연결이 roles 행을 잡은 채 같은 트랜잭션에서 admin 을 주고 커밋한다(외래 키가 roles 행을 읽어서
	 * 다른 연결로는 줄 수 없다).
	 */
	@Test
	@Timeout(90)
	void 처리기가_본_뒤에_보유자가_된_대상도_비활성화는_409() throws Exception {
		Login probe = login("probe_users");
		UUID x = user();
		CompletableFuture<HttpResponse<String>> sent;
		try (Connection c = f.db()) {
			c.setAutoCommit(false);
			try (Statement s = c.createStatement()) {
				s.execute("SET lock_timeout = '5s'");
				s.execute("SET statement_timeout = '30s'");
				s.executeQuery("SELECT id FROM roles WHERE is_superuser FOR UPDATE").close();
			}
			try {
				sent = deactivating(probe.http(), x).get();
				awaitWaiters(1);
				try (PreparedStatement g = c.prepareStatement(
						"INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE key = 'admin'")) {
					g.setObject(1, x);
					g.executeUpdate();
				}
				c.commit();
			}
			finally {
				c.rollback();
			}
		}
		HttpResponse<String> r = sent.get(30, TimeUnit.SECONDS);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
		assertThat(r.body()).contains(LAST_ADMIN);
		assertThat(active(x)).isTrue();
		assertThat(one("SELECT count(*) FROM operation_logs WHERE actor_user_id = ? AND action = 'user.denied' "
				+ "AND target_id = ? AND summary = 'user.deactivate last_admin'", probe.id(), x.toString())).isEqualTo(1);
	}
}
