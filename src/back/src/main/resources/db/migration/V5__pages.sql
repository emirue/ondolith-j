-- Phase 1-C1: 페이지 표와 권한 다섯(스펙 docs/superpowers/specs/2026-11-24-phase1c1-pages-sanitize-design.md 2절).
-- 마이그레이션은 앞으로만 간다(NFR-303 결정). enum Permission 에 같은 커밋으로 더했다 — 아니면 기동 점검이 멈춘다.

-- slug·title CHECK 는 원문에 없다 — 앱 검사를 못 지난 값이 공개 주소·API 경로에 들어가지 않게 하는 마지막 둑이다.
-- 예약어는 DB 에 두지 않는다(목록이 릴리스마다 는다). ponytail: 목록 인덱스 없음 — 느려지면 pages (updated_at DESC, id)
CREATE TABLE pages (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       text        NOT NULL UNIQUE CHECK (length(slug) <= 100 AND slug ~ '^[a-z0-9][a-z0-9-]*$'),
    title      text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    body_html  text        NOT NULL CHECK (length(body_html) <= 200000),
    status     text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO permissions (key, description) VALUES
    ('page.view', '페이지 목록을 본다'),
    ('page.create', '페이지를 만든다'),
    ('page.update', '페이지를 열어 고친다'),
    ('page.delete', '페이지를 지운다'),
    ('page.publish', '페이지를 발행하고 내린다');

-- 방문자 권한이 아니다 — anonymous·member 에는 주지 않는다(R9). admin 은 is_superuser 라 행이 없다
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.key IN ('editor', 'operator') AND p.key LIKE 'page.%';
