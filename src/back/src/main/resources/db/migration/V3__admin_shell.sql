-- Phase 1-B1: 작업 로그 표와 관리자 권한 셋(스펙 docs/superpowers/specs/2026-11-21-phase1b1-admin-shell-design.md 2절).
-- 마이그레이션은 앞으로만 간다(NFR-303 결정).

-- 표·CHECK·FK·인덱스·트리거는 ondolith 원문 D30(30-data-model.md 「operation_logs」)과 docs/schema.sql 을 그대로 옮겼다.
-- 원문은 Phase 2 표다. 1-B 가 첫 관리 작업이라 앞당긴다.
-- action·target_type 은 열거가 아니라 형식만 CHECK 로 고정한다 — 동작이 늘 때마다 이 표를 잠그는 마이그레이션을 만들지 않게(D30).
-- 보존 기한이 없고 지우지 않는다(D30, D13 A-601).
CREATE TABLE operation_logs (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 사용자를 지워도 기록은 남는다(D30 3-1). 누구였는지는 actor_email 스냅샷이 말한다
    actor_user_id uuid        REFERENCES users(id) ON DELETE SET NULL,
    -- '' = 시스템 주체
    actor_email   text        NOT NULL DEFAULT '' CONSTRAINT operation_logs_actor_email_check CHECK (length(actor_email) <= 254),
    action        text        NOT NULL CONSTRAINT operation_logs_action_check CHECK (action ~ '^[a-z][a-z0-9]*\.[a-z][a-z0-9_]*$'),
    target_type   text        NOT NULL CONSTRAINT operation_logs_target_type_check CHECK (target_type ~ '^[a-z][a-z0-9_]*$'),
    -- uuid 가 아니다: 설정 키처럼 uuid PK 가 없는 대상도 있다(D30)
    target_id     text        CONSTRAINT operation_logs_target_id_check CHECK (target_id IS NULL OR length(target_id) <= 255),
    summary       text        NOT NULL DEFAULT '' CONSTRAINT operation_logs_summary_check CHECK (length(summary) <= 2000),
    ip            inet,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX operation_logs_created_at_idx ON operation_logs (created_at DESC);
CREATE INDEX operation_logs_actor_idx ON operation_logs (actor_user_id);

-- append-only 는 트리거로 강제한다(D30, D86 5.11).
-- 한계(원문과 같다): 앱이 표 소유자라 DROP TRIGGER 가 된다. 공격자가 아니라 우리 코드의 사고를 막는다.
CREATE FUNCTION operation_logs_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION '작업 로그는 수정·삭제할 수 없습니다 (D15 7절)';
END;
$$;

CREATE TRIGGER operation_logs_no_delete BEFORE DELETE ON operation_logs
    FOR EACH ROW EXECUTE FUNCTION operation_logs_append_only();
-- PostgreSQL 은 ON DELETE SET NULL 을 내부 UPDATE ... SET actor_user_id = NULL 로 돈다. WHEN 이 그 하나만 통과시킨다 —
-- UPDATE 를 통째로 막으면 사용자 삭제가 실패한다(D30 측정).
CREATE TRIGGER operation_logs_no_update BEFORE UPDATE ON operation_logs
    FOR EACH ROW WHEN (NEW.actor_user_id IS NOT NULL OR OLD.actor_user_id IS NULL)
    EXECUTE FUNCTION operation_logs_append_only();
-- 위 WHEN 은 actor 를 NULL 로 바꾸며 다른 칸도 함께 바꾸는 UPDATE 를 막지 못한다. 그 밖의 칸이 SET 에 있으면 거부한다(원문 00021).
CREATE TRIGGER operation_logs_no_update_cols
    BEFORE UPDATE OF id, actor_email, action, target_type, target_id, summary, ip, created_at
    ON operation_logs
    FOR EACH ROW EXECUTE FUNCTION operation_logs_append_only();
-- 행 트리거는 TRUNCATE 를 보지 못한다(원문 00023).
CREATE TRIGGER operation_logs_no_truncate BEFORE TRUNCATE ON operation_logs
    FOR EACH STATEMENT EXECUTE FUNCTION operation_logs_append_only();

-- 관리자 권한 셋(D15 2.1). enum Permission 에 같은 커밋으로 더했다 — 아니면 기동 점검이 DB·코드 키 차이로 멈춘다.
INSERT INTO permissions (key, description) VALUES
    ('settings.view', '시스템 정보를 본다'),
    ('settings.update', '사이트 설정을 바꾼다'),
    ('log.view', '작업 로그를 본다');

-- D15 2.5: 운영자에게만 준다. 편집자는 관리자 트리에 들어오지만 이 셋은 없다. admin 은 is_superuser 라 행 없이 통과한다.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.key = 'operator' AND p.key IN ('settings.view', 'settings.update', 'log.view');
