package dev.ondolithj.shared;

import dev.ondolithj.auth.Actor;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 작업 로그 기록기(1-B1 스펙 4.1). 운영 컨텍스트에만 있다. 본 작업이 끝난 뒤 문장 하나로 남기고, 실패해도 요청을 실패시키지 않는다 —
 * 부르는 곳에 @Transactional 을 달지 않는다(같은 트랜잭션이면 기록 실패가 일을 되돌린다). 요청 객체를 받지 않는다(4.3).
 * ponytail: 일과 기록 사이에 프로세스가 죽으면 기록이 빠진다 — 돈 작업(Phase 3) 전에 같은 트랜잭션 기록으로 올릴지 다시 본다.
 */
@Component
public class OperationLog {

	private static final Logger log = LoggerFactory.getLogger(OperationLog.class);

	/** 동작 이름과 target_type 을 함께 정한다. */
	public enum Action {
		AUTH_LOGIN("auth.login", "user"), SETTINGS_UPDATE("settings.update", "settings"),
		USER_CREATE("user.create", "user"), USER_DEACTIVATE("user.deactivate", "user"), USER_ACTIVATE("user.activate", "user"), USER_DENIED("user.denied", "user");

		private final String key;

		private final String targetType;

		Action(String key, String targetType) {
			this.key = key;
			this.targetType = targetType;
		}

		public String key() {
			return key;
		}

		public String targetType() {
			return targetType;
		}
	}

	// inet 으로 읽을 수 없는 IP(영역 ID 가 붙은 IPv6 등)는 NULL — 캐스트 실패로 행을 잃지 않게.
	// CASE 안에 ?::inet 을 바로 두면 플래너가 바인드 값을 상수로 접어 캐스트를 먼저 평가한다(22P02).
	// MATERIALIZED CTE 의 칸은 상수가 아니라 접히지 않는다
	private static final String SQL = """
			WITH v AS MATERIALIZED (SELECT ?::text AS ip)
			INSERT INTO operation_logs (actor_user_id, actor_email, action, target_type, target_id, summary, ip)
			SELECT ?, ?, ?, ?, ?, ?, CASE WHEN pg_input_is_valid(v.ip, 'inet') THEN v.ip::inet END FROM v
			""";

	private final JdbcClient jdbc;

	OperationLog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void record(Actor actor, Action action, String targetId, String summary, String ip) {
		try {
			jdbc.sql(SQL).params(ip, actor.id(), actor.email(), action.key(), action.targetType(), targetId, summary)
				.update();
		}
		// 예외 메시지에는 「Failing row contains (…)」처럼 이메일·IP 가 들어간다 — 동작 이름과 SQLState 만 남긴다
		catch (DataAccessException e) {
			log.error("작업 로그를 남기지 못했습니다: 동작={} SQLState={}", action.key(), sqlState(e));
		}
	}

	/** 가장 안쪽 SQLException 의 SQLState. 없으면 "-". */
	private static String sqlState(Throwable e) {
		String state = "-";
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException s && s.getSQLState() != null) {
				state = s.getSQLState();
			}
		}
		return state;
	}
}
