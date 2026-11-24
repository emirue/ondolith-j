package dev.ondolithj.admin;

import dev.ondolithj.auth.ActorQuery;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 마지막 관리자 잠금(1-B2 스펙 5절). 비활성화와 superuser 역할 빼기는 대상이 보유자가 아니어 보여도 늘 이것을 거친다 — 대상은 잠금을
 * 얻은 뒤 같은 트랜잭션에서 다시 읽는다. 기록하지 않는다: 부르는 곳이 커밋·롤백 뒤에 남긴다.
 */
@Component
public class LastAdmin {

	public enum Outcome {
		DONE, REFUSED, GONE, R6
	}

	private record Target(boolean active, boolean holder) {
	}

	private static final String TARGET = "SELECT u.is_active AS active, " + ActorQuery.HOLDS_SUPERUSER
			+ " AS holder FROM users u WHERE u.id = ?";

	private static final String HOLDERS = "SELECT count(*) FROM users u WHERE u.is_active AND " + ActorQuery.HOLDS_SUPERUSER;

	private final TransactionTemplate tx;

	private final JdbcClient jdbc;

	LastAdmin(TransactionTemplate tx, JdbcClient jdbc) {
		this.tx = tx;
		this.jdbc = jdbc;
	}

	/** 요청자가 superuser 인 쓰기(superuser 역할 빼기 — R3 이 이미 봤다). */
	public Outcome run(UUID target, IntSupplier write) {
		return run(target, true, write);
	}

	/**
	 * 대상이 없거나 쓰기가 0행이면 GONE. 대상이 보유자인데 요청자가 superuser 가 아니면 R6 — 처리기가 잠금 앞에서 본 R6 을 잠금 아래서
	 * 다시 본다(처리기가 읽은 뒤 대상이 보유자가 되는 틈, Task 6 관찰). 대상이 활성 보유자이고 활성 보유자가 1명 이하면 REFUSED.
	 */
	public Outcome run(UUID target, boolean requesterSuperuser, IntSupplier write) {
		return tx.execute(status -> {
			// 직렬화: 보유자를 줄이는 쓰기는 모두 이 행을 먼저 잡는다
			jdbc.sql("SELECT id FROM roles WHERE is_superuser FOR UPDATE").query(UUID.class).list();
			Target t = jdbc.sql(TARGET).param(target).query(Target.class).optional().orElse(null);
			if (t == null) {
				return Outcome.GONE;
			}
			if (t.holder() && !requesterSuperuser) {
				status.setRollbackOnly();
				return Outcome.R6;
			}
			if (t.active() && t.holder() && jdbc.sql(HOLDERS).query(Long.class).single() <= 1) {
				status.setRollbackOnly();
				return Outcome.REFUSED;
			}
			return write.getAsInt() == 0 ? Outcome.GONE : Outcome.DONE;
		});
	}
}
