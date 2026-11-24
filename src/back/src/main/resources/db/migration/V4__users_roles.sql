-- Phase 1-B2: 사용자·역할 권한 여섯(스펙 docs/superpowers/specs/2026-11-22-phase1b2-users-roles-design.md 2절).
-- 마이그레이션은 앞으로만 간다(NFR-303 결정).

-- M-5: V3 의 「WHEN 이 그 하나만 통과시킨다」는 사실보다 넓다: 사용자가 남아 있어도 SET actor_user_id = NULL 만 하는
-- UPDATE 는 통과한다. 원문과 같은 한계, actor_email 은 못 바꾼다. V3 는 체크섬 때문에 고치지 않는다.

-- D15 2.2·2.5 의 키 중 1-B2 가 쓰는 것만 심는다. enum Permission 에 같은 커밋으로 더했다 — 아니면 기동 점검이 멈춘다.
INSERT INTO permissions (key, description) VALUES
    ('user.view', '사용자 목록을 본다'),
    ('user.create', '사용자를 만든다'),
    ('user.update', '사용자 상세를 보고 활성 상태를 바꾼다'),
    ('role.view', '역할과 권한 구성을 본다'),
    ('role.assign', '사용자에게 역할을 주고 뺀다'),
    ('role.manage', '역할에 권한을 주고 뺀다');

-- 운영자는 보기 둘만 받는다. 편집자에게는 주지 않는다. 나머지 넷은 admin(is_superuser, 행 없음)만 갖는다 —
-- 방어가 아니다(D15 5.1 끝): 설치처가 A-404 로 다른 역할에 줄 수 있다.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.key = 'operator' AND p.key IN ('user.view', 'role.view');
