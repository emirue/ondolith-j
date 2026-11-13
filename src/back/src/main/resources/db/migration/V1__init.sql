-- 첫 스키마. 표 모양은 ondolith 원문(internal/migrations 00001·00002·00003·00004, D30)을 따른다.
-- 마이그레이션은 앞으로만 간다(NFR-303 결정). 되돌리기는 업그레이드 전 백업 복원이다.
-- 권한(permissions·role_permissions)과 세션 표는 Phase 1 에서 더한다.

-- 이메일은 앱이 소문자로 저장하므로 UNIQUE 가 대소문자를 구분하지 않는 셈이다.
-- ondolith 의 is_admin 은 역할 표로 옮기기 전의 임시 칸이라 넣지 않는다.
-- sessions_valid_from 이 NOT NULL 인 이유: NULL 이면 「기준 없음」이 되어 비교가 열린 쪽으로 실패한다.
CREATE TABLE users (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email               text        NOT NULL UNIQUE,
    password_hash       text        NOT NULL,
    display_name        text        NOT NULL DEFAULT '',
    is_active           boolean     NOT NULL DEFAULT true,
    sessions_valid_from timestamptz NOT NULL DEFAULT now(),
    email_verified_at   timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- key 에 점이 없다: 점은 권한 키 <resource>.<action> 의 구분자다.
CREATE TABLE roles (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    key           text        NOT NULL UNIQUE CHECK (key ~ '^[a-z][a-z0-9_]*$'),
    name          text        NOT NULL,
    description   text        NOT NULL DEFAULT '',
    is_builtin    boolean     NOT NULL DEFAULT false,
    is_superuser  boolean     NOT NULL DEFAULT false,
    -- anonymous·member 는 암묵적으로 주어지므로 user_roles 가 가리키면 안 된다. DB 가 막을 수 있는 유일한 칸이다.
    is_assignable boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- 슈퍼유저 역할은 하나뿐이다. 처리기가 아니라 DB 가 강제한다.
CREATE UNIQUE INDEX roles_one_superuser_idx ON roles ((is_superuser)) WHERE is_superuser;

CREATE TABLE user_roles (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id    uuid        NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_roles_uniq UNIQUE (user_id, role_id)
);

-- 속도가 아니라, 보유자가 있는 역할의 삭제를 거부할 때 세는 색인이다.
CREATE INDEX user_roles_role_id_idx ON user_roles (role_id);

-- 사이트 이름·유형(site.name·site.type) 등. 관리자가 바꾸는 값이라 설정 파일이 아니라 여기 둔다.
CREATE TABLE settings (
    key        text        PRIMARY KEY,
    value      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 내장 역할. 코드가 이 다섯을 근거로 판단하므로 is_builtin 이다.
INSERT INTO roles (key, name, description, is_builtin, is_superuser, is_assignable) VALUES
    ('anonymous', '익명',   '세션에 사용자가 없는 요청자. 모든 요청자의 바닥값',   true, false, false),
    ('member',    '회원',   '인증된 요청자 전원',                                  true, false, false),
    ('editor',    '편집자', '콘텐츠를 만진다. 돈과 설정은 못 만진다',              true, false, true),
    ('operator',  '운영자', '사이트를 굴린다. 게시판·상품·주문·환불·설정',         true, false, true),
    ('admin',     '관리자', '권한 체계를 바꿀 수 있는 유일한 역할',                true, true,  true);
