-- Phase 1-A: 권한과 세션 표(스펙 docs/superpowers/specs/2026-11-14-phase1a-auth-design.md 2절).
-- 마이그레이션은 앞으로만 간다(NFR-303 결정).

-- 권한 목록은 코드(enum Permission)가 정하고 여기서 심는다(ondolith D15 P1). 관리 UI 로 늘리지 않는다.
-- 키는 <resource>.<action>. 쓰는 권한만 심는다 — 미리 심으면 기동 점검의 「미사용 권한」 경고가 무시된다(D15 W1-04).
CREATE TABLE permissions (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    key         text        NOT NULL UNIQUE CHECK (key ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'),
    description text        NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- 게시판별 권한 칸(board_id)은 Phase 2 에서 더한다.
CREATE TABLE role_permissions (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id       uuid        NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id uuid        NOT NULL REFERENCES permissions(id) ON DELETE RESTRICT,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT role_permissions_uniq UNIQUE (role_id, permission_id)
);

INSERT INTO permissions (key, description) VALUES
    ('admin.access', '관리자 화면에 들어간다');

-- D15 2.5: 관리자 트리 진입은 편집자·운영자. admin 은 is_superuser 라 행 없이 모든 검사를 통과한다.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.key IN ('editor', 'operator') AND p.key = 'admin.access';

-- Spring Session JDBC 4.1.1 의 org/springframework/session/jdbc/schema-postgresql.sql 을 그대로 옮겼다.
-- 자동 생성(spring.session.jdbc.initialize-schema)은 끄고 스키마는 마이그레이션으로만 바꾼다(NFR-302).
-- 백업할 때 이 두 표의 데이터는 뺀다(세션 토큰, ondolith D60 「백업 내려받기」) — 백업 기능에서 지킨다.
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
