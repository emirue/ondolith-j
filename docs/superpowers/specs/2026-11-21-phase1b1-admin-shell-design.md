# Phase 1-B1 설계 — 관리자 셸 기반·사이트 설정·작업 로그·시스템 정보

작성 2026-11-21. 사용자에게 위임받은 역할 에이전트(제품·보안)가 정한 결정을 옮겼다 — **사용자 확인 전**이다. 결정 요약은 `docs/decisions.md` 「Phase 1-B 결정」(Q1~Q17).
근거: 1-A 스펙 `docs/superpowers/specs/2026-11-14-phase1a-auth-design.md`, ondolith 원문 D10(FR-701·703·707·724~727)·D13(A-101·201·601·602)·D15(1.2, 7절)·D19(A-201)·D30(`operation_logs`)·D81·D86.
기능 상세의 단일 출처는 ondolith 원문이다. 이 문서는 SPA·Spring 에 맞게 바꾼 것과 1-B1 의 경계를 적는다.

## 0. 범위

| 넣는다 | 뺀다(해당 하위 Phase 로) |
|---|---|
| 리소스 권한 표시 `@AdminPermission(Permission.X)`와 기동 점검 규칙 (Q1) | 재인증 15분 창·`POST /api/admin/reauth`·세션 셋째 속성 `reauth_at` (Q2·Q16) — 1-B2 |
| V3: `operation_logs`·append-only 트리거 넷, 권한 셋 씨앗 (D30) | A-401·A-402 사용자 목록·생성·비활성·활성, 마지막 관리자 잠금 (Q10) — 1-B2 |
| 작업 로그 기록기, 로그인 성공 기록 `auth.login` (Q13) | A-403·A-404·A-405 역할·권한·부여, R1~R6·R9 거절, FR-723 빼기 — 1-B2 |
| 클라이언트 IP 함수 하나 — 횟수 제한과 작업 로그가 함께 쓴다 (Q15) | FR-725 확인 대화상자 — 1-B2(아래 6절). 사용자 삭제·재설정 강제(Q11·Q14), 역할 생성(Q7) — 그 뒤 |
| 관리자 셸: 메뉴 = `/api/me` 의 `can` (FR-701) | 대시보드 위젯(FR-702)·메뉴 숨김(FR-720) (Q6) |
| 공용 부품: 저장 성공 알림 `role="status"`(FR-724), 두 번 보내기 방지(FR-727) | A-201 의 `site.name` 밖 칸(유형·가입·Turnstile·사업자 등) (Q5) |
| A-201 사이트 이름 (`site.name`) | A-601 기간 조회(FR-712)와 그 시간대 (Q4·Q17) |
| A-601 작업 로그 목록(기간 조건 없음, 쪽당 100) | 헬스의 버전 노출 변경 (Q12, 사용자 확인 필요) |
| A-602 시스템 정보: jar 버전·Flyway 적용 목록·대기 수·DB 서버 판. DSN 없음 | 실제 클라이언트 IP(CloudFront 뒤) 판정 — Phase 4 |

1-B1 이 끝나면: 운영자(`operator`)와 관리자가 `/admin` 에서 권한에 맞는 메뉴만 보고, 사이트 이름을 바꾸면 화면 위에 알림이 한 번 뜨고 작업 로그에 남으며, 작업 로그와 시스템 정보를 본다. 편집자(`editor`)는 관리자 트리에 들어오지만 세 메뉴가 모두 없다.

## 1. 기술

| 항목 | 선택 | 근거 |
|---|---|---|
| 리소스 권한 표시 | 메타 애너테이션 `@AdminPermission`: `@PreAuthorize("hasAuthority('ADMIN_ACCESS') and hasAuthority('{value}')")`, 인자는 enum 하나 | 1-A 에서 동작하는 단일 enum 템플릿과 SpEL `and` 만 쓴다. enum 배열 템플릿은 값이 따옴표 없는 쉼표 문자열이 되는 것으로 보여 버렸다(Q1) |
| 작업 로그 쓰기 | `JdbcClient` 문장 하나, 본 작업이 끝난 뒤 따로 | PostgreSQL 은 트랜잭션 안의 INSERT 가 실패하면 트랜잭션 전체가 중단된다 — 「기록 실패가 일을 되돌리지 않는다」를 지키려면 같은 트랜잭션에 넣을 수 없다 |
| 시스템 정보 | Boot 가 만든 `Flyway` 빈의 `info()`(적용·대기), `BuildProperties`, `SHOW server_version` | 이미 있는 것. Flyway info API 의 이름·모양은 구현 때 확인 |
| 관리자 라우팅 | React Router 중첩 라우트(`/admin` 아래 화면들) | 1-A 에서 들였다. 중첩·`NavLink` API 는 구현 때 확인 |
| 두 번 보내기 방지 | TanStack Query 변경 + 동기 잠금(`useRef`) | `isPending` 은 다음 그리기에서야 보여, 빠른 두 번 누름이 둘 다 지나갈 수 있다(추정, 시험으로 확인) |

새 의존성은 없다. 확인 대화상자(Radix AlertDialog)는 이미 있는 `radix-ui` 에 들어 있지만 1-B1 에서는 쓰지 않는다.

## 2. 데이터 (V3 마이그레이션, 앞으로만)

- `operation_logs` — 원문 D30:805-865 의 표와 `schema.sql:328-343` 의 CHECK, FK(`schema.sql:2177`)를 그대로 옮긴다(원문은 Phase 2 표다. 1-B 가 첫 관리 작업이라 앞당긴다).
  `id uuid PK DEFAULT gen_random_uuid()`, `actor_user_id uuid NULL REFERENCES users(id) ON DELETE SET NULL`(D30 3-1), `actor_email text NOT NULL DEFAULT '' CHECK (length ≤ 254)`(스냅샷, `''` = 시스템), `action text NOT NULL CHECK (~ '^[a-z][a-z0-9]*\.[a-z][a-z0-9_]*$')`, `target_type text NOT NULL CHECK (~ '^[a-z][a-z0-9_]*$')`, `target_id text NULL CHECK (≤ 255)`, `summary text NOT NULL DEFAULT '' CHECK (≤ 2000)`, `ip inet NULL`, `created_at timestamptz NOT NULL DEFAULT now()`.
- 인덱스: `operation_logs_created_at_idx (created_at DESC)`, `operation_logs_actor_idx (actor_user_id)`(D30).
- append-only(D30 「append-only는 트리거로 강제한다」, D86 5.11). 함수 `operation_logs_append_only()` 는 `RAISE EXCEPTION` 하나:
  ```sql
  BEFORE DELETE ... FOR EACH ROW                                        -- no_delete
  BEFORE UPDATE ... FOR EACH ROW WHEN (NEW.actor_user_id IS NOT NULL OR OLD.actor_user_id IS NULL)  -- no_update
  BEFORE UPDATE OF id, actor_email, action, target_type, target_id, summary, ip, created_at ... FOR EACH ROW  -- no_update_cols
  BEFORE TRUNCATE ... FOR EACH STATEMENT                                -- no_truncate
  ```
  PostgreSQL 은 `ON DELETE SET NULL` 을 내부 `UPDATE ... SET actor_user_id = NULL` 로 돈다. `no_update` 의 WHEN 이 그 하나만 통과시키고, `no_update_cols` 는 SET 에 다른 칸이 섞이면 막는다. 사용자 삭제는 1-B 밖이지만(Q14) 이 예외를 지금 둔다 — 아래 설치 원복이 이 경로다.
  한계(원문과 같다): 앱이 표 소유자라 `DROP TRIGGER` 가 된다. 우리 코드의 사고를 막는 것이지 공격자를 막는 것이 아니다.
- **설치 실패 원복(`InstallService.removeAdmin`)은 깨지지 않는다.** ① 설치 컨텍스트에는 로그인이 없어 그 관리자를 가리키는 로그 행이 0 이다 — SET NULL 이 건드릴 행이 없다. ② 행이 있더라도 `DELETE FROM users` 의 SET NULL 은 `no_update` 의 WHEN 을 통과하고, SET 에 actor 밖 칸이 없어 `no_update_cols` 도 걸리지 않는다. ③ `removeAdmin` 은 TRUNCATE 를 쓰지 않는다. 기존 `InstallApiTest` 「설정 파일 교체가 실패하면 관리자를 지우고 재시도 가능」이 V3 와 함께 돌아 이것을 확인한다.
- 권한 씨앗: `settings.view`(시스템 정보 조회), `settings.update`(사이트 설정 변경), `log.view`(작업 로그 열람). 셋 다 `operator` 에 준다(D15 2.5). `editor` 에는 주지 않는다. enum `Permission` 에 같은 커밋으로 `SETTINGS_VIEW`·`SETTINGS_UPDATE`·`LOG_VIEW` 를 더한다 — 아니면 기동 점검 2번이 기동을 멈춘다. 1-B2 의 권한은 V4 가 심는다.

## 3. 권한 표시와 기동 점검 (Q1)

- 표시는 셋이다: `@RequiresPermission(X)`, `@AdminPermission(X)`(`admin.access` 와 X 를 함께 요구), `@PublicApi`. 메서드·클래스 합쳐 정확히 하나(1-A 규칙 그대로, 메타 애너테이션까지 센 `@PreAuthorize` 도 하나).
- `/api/admin`·`/api/admin/**` 의 처리기는 `@AdminPermission(…)` 또는 `@RequiresPermission(ADMIN_ACCESS)` 만 단다. 다른 권한의 `@RequiresPermission`·`@PublicApi` 는 기동을 멈춘다 — `admin.access` 가 빠질 길을 식(애너테이션)과 경로 점검 두 겹으로 막는다.
- 「아무 메서드도 쓰지 않는 권한」 경고는 `@AdminPermission` 의 값도 쓴 것으로 센다.
- 새 관리자 컨트롤러는 클래스 표시 없이 메서드마다 단다(관례, 점검하지 않는다). 지금의 `AdminController`(클래스에 `ADMIN_ACCESS`)는 그대로 둔다. 관리자 경로 밖의 `@AdminPermission` 은 막지 않는다(더 엄격할 뿐이다).

## 4. 작업 로그

### 4.1 기록기 `shared/OperationLog`

- 운영 컨텍스트에만 있다. 설치 컨텍스트는 `install` 패키지만 스캔하고 그 밖은 `@Import` 로 명시한 클래스만 들여오므로(`InstallApplication`) 기록기가 설치 쪽에 생기지 않는다 — `LauncherTest`·설치 E2E 가 지킨다.
- 부르는 모양: `record(Actor actor, Action action, String targetId, String summary, String ip)`. 호출자가 `ClientIp.of(request)` 로 IP 값만 넘긴다 — 기록기는 요청 객체를 받지 않는다(4.3). `Action` 은 같은 파일의 enum 이고 동작 이름과 `target_type` 을 함께 정한다. 로그인 지점은 이미 `ActorQuery` 로 `Actor` 를 읽는다.
- 순서: **본 작업이 커밋된 뒤** 문장 하나로 INSERT 한다. 실패하면 요청을 실패시키지 않고 ERROR 로그만 남긴다(원문 코드와 같다). **그 로그에는 동작 이름과 SQLState 만 넣고 예외 메시지·스택을 넣지 않는다** — PostgreSQL 제약 위반 메시지의 「Failing row contains (…)」에 이메일·IP 가 들어 1-A 의 「로그에 이메일 없음」을 깬다. `ponytail:` 일과 기록 사이에 프로세스가 죽으면 기록이 빠진다 — 돈 작업(Phase 3) 전에 같은 트랜잭션 기록으로 올릴지 다시 본다.
- `ip` 는 클라이언트 IP 함수 값. `inet` 으로 읽을 수 없는 값(영역 ID 가 붙은 IPv6 등)은 NULL 로 넣는다(`pg_input_is_valid`, PostgreSQL 16+, 구현 때 확인) — 캐스트 실패로 행을 잃지 않게.

### 4.2 남기는 동작 (1-B1)

| `action` | 언제 | `actor_*` | `target_type` / `target_id` | `summary` |
|---|---|---|---|---|
| `auth.login` | 로그인 성공(204) 직전. 실패는 남기지 않는다 — 앱 로그 INFO 만(Q13) | 로그인한 사용자 / DB 의 이메일 | `user` / 그 사용자 id | `''` |
| `settings.update` | A-201 저장에서 값이 실제로 바뀌었을 때만(D19 A-201) | 요청자 / 요청자 이메일 | `settings` / NULL | 바뀐 키 이름(`site.name`). 값은 넣지 않는다 |

- D15 7절 「이 표에 없는 SC-5 화면은 기록하지 않는다」를 따른다. 1-B1 의 SC-5 는 A-201 하나다.
- 원문과 다른 곳: D15 7절은 로그인 실패도 남기라 한다. 작업 로그는 지울 수 없고 보존 기한이 없어서, 실패를 넣으면 익명 공격자가 IP·계정을 바꿔 가며 지울 수 없는 표를 키운다(Q13).

### 4.3 비밀번호·토큰이 어떤 칸에도 들어가지 않게

- 칸마다 출처를 코드로 고정한다: `action`·`target_type` 은 enum 상수, `actor_email` 은 DB 에서 읽은 `Actor`, `target_id` 는 UUID 나 키 이름, `summary` 는 코드에 있는 키 이름만, `ip` 는 IP 함수. **기록기는 요청 본문·헤더·쿠키를 받는 인자가 없다.**
- 시험이 실제 흐름(로그인·저장)을 돌린 뒤 그 계정의 모든 행을 `row_to_json` 텍스트로 읽어 로그인 비밀번호, 세션 쿠키 값, XSRF 토큰, DB 비밀번호가 없음을 단언한다(D81 W2-23, D86 5.10).

### 4.4 클라이언트 IP 함수 `shared/ClientIp` (Q15)

- `ClientIp.of(request)` 하나. 지금 값은 `request.getRemoteAddr()`. 로그인 횟수 제한·관리자 횟수 제한·작업 로그가 모두 이것을 부른다. `ponytail:` CloudFront 뒤의 실제 IP 는 Phase 4 배포 전에 믿을 프록시를 정해 여기 한 곳만 고친다. `X-Forwarded-For` 는 믿지 않는다.

## 5. API

| 경로 | 표시 | 요청 | 응답 |
|---|---|---|---|
| `POST /api/admin/settings` (A-201) | `@AdminPermission(SETTINGS_UPDATE)` | `{"name": "..."}` | 204 / 400 / 401 / 403 / 422 / 429 |
| `GET /api/admin/oplog?page=1` (A-601) | `@AdminPermission(LOG_VIEW)` | `page` 1 이상 정수, 기본 1 | 200 / 400 / 401 / 403 / 422 / 429 |
| `GET /api/admin/system` (A-602) | `@AdminPermission(SETTINGS_VIEW)` | — | 200 / 401 / 403 / 429 |
| `POST /api/auth/login` (동작 추가) | 공개 | 그대로 | 그대로. 성공하면 `auth.login` 행 |
| `GET /api/me` (키 추가) | 공개 | — | `can` 키가 넷이 된다(`admin.access`·`settings.view`·`settings.update`·`log.view`) |
| `GET /api/admin` | `@RequiresPermission(ADMIN_ACCESS)` | — | 그대로 200 `{}`. 대시보드는 뒤로(Q6), 메뉴는 `can` 으로 그린다 |

- JSON 은 snake_case. 오류는 모두 Problem Details, 422 는 최상위 `errors{칸: 메시지}`(Phase 0 규약). 400 은 읽을 수 없는 본문·`page` 가 숫자가 아님(Spring 기본, 구현 때 확인).
- **A-201** `POST /api/admin/settings`: 고정 레코드 `name` 하나 — 다른 칸(`type` 등)은 읽지 않는다(D86 2.5). 앞뒤 공백을 빼고(설치와 같은 `strip`) 1~100자, 비거나 공백만이면 422 `errors.name`. 저장은 문장 하나 — `INSERT ... ON CONFLICT (key) DO UPDATE ... WHERE settings.value IS DISTINCT FROM EXCLUDED.value` 가 행을 돌려주면 바뀐 것이고 그때만 기록한다(동시 저장에도 셈이 맞다). CSRF 필요.
  폼의 처음 값은 공개 `GET /api/site` 의 `name` 을 쓴다. `ponytail:` A-201 이 공개 칸만 가져 조회 API 를 따로 두지 않는다 — 비공개 칸(사업자·Turnstile)이 생기면 `GET /api/admin/settings`(`settings.update`, D19)를 더한다.
- **A-601** `GET /api/admin/oplog`: `{"items": [{"id", "created_at", "actor_user_id", "actor_email", "action", "target_type", "target_id", "summary", "ip"}], "page": 1, "page_size": 100, "total": 3}`. `created_at DESC, id DESC`, `LIMIT 100 OFFSET (page-1)*100`(offset 은 long 으로 계산), 전체 건수를 센다(D86 7.13). 질의는 행 수와 관계없이 둘이다(목록·셈). `ip` 는 `host(ip)` — 넷마스크 없이. `page` 가 1 미만이면 422 `errors.page`, 끝을 넘으면 빈 `items`.
- **A-602** `GET /api/admin/system`: `{"version": "0.1.0", "migrations": [{"version": "1", "description": "init", "installed_on": "...", "success": true}], "pending_count": 0, "database": {"server_version": "18.1"}}`. 칸은 이것뿐이다 — Flyway 정보 객체를 그대로 내지 않는다(`installed_by` 는 DB 계정 이름이다). DSN·호스트·포트·DB 계정은 어디에도 없다(D81 W1-43). 원문의 「DB 연결 상태」는 `server_version`(`SHOW server_version`)으로 보인다 — 값이 있으면 연결된 것이다. `connected` 칸은 두지 않는다: 관리자 요청은 권한을 읽으려고 DB 를 먼저 쳐서, DB 가 끊기면 처리기에 닿기 전에 실패한다. `false` 가 나올 길이 없어 시험(Red)을 만들 수 없는 분기다(설계 검토에서 뺐다). 헬스는 바꾸지 않는다(Q12 임시안).
- 모든 새 API 는 `/api/admin/**` 라 1-A 의 관리자 IP 60회/분과 CSRF 를 그대로 받는다. OpenAPI 문서에 실제 응답 코드를 적고 생성 시험이 확인한다.

## 6. 프런트

- 라우트: `/admin` 아래 중첩. 화면 표 하나(`features/admin/AdminLayout.tsx`)가 `{경로, 이름, 권한 키, 화면}` 을 갖고, **라우트와 메뉴를 둘 다 이 표에서 만든다**(D81 「메뉴는 등록된 라우트에서 파생」). 메뉴는 `can[권한 키]` 가 `true` 인 항목만 그린다 — 없는 항목은 DOM 에 없다(FR-701 완료 기준). 숨김은 보안이 아니다. 주소를 직접 쳐서 들어오면 서버가 403 을 내고 화면은 「권한이 없습니다」.
  | 경로 | 이름 | 권한 |
  |---|---|---|
  | `/admin/settings` | 사이트 설정 | `settings.update` |
  | `/admin/oplog` | 작업 로그 | `log.view` |
  | `/admin/system` | 시스템 정보 | `settings.view` |
- 셸: 1-A 의 `/admin` 문(`GET /api/admin` 401·403 분기)을 그대로 두고, 통과하면 제목 「관리자」 + `<nav aria-label="관리자 메뉴">` + 로그아웃 + 하위 화면. `/admin` 자체는 빈 대시보드(Q6).
- **공용 변경 훅 `shared/api/useSave.ts`** — 상태를 바꾸는 요청은 모두 이것을 쓴다.
  - 성공 문구가 **필수 인자**다. 빠뜨리면 타입 검사가 실패한다(원문 W6-45 를 옮긴다).
  - FR-727: 보내는 동안 다시 불러도 보내지 않는다(동기 잠금). 단추 글이 「처리 중…」이고 `disabled` 대신 `aria-disabled` 를 쓴다(누른 단추의 초점이 사라지지 않게). 성공이든 실패든 끝나면 풀린다. 필수 칸이 빈 제출(클라이언트 검증 실패)은 잠그지 않는다. `ponytail:` 65초 시한이 없다 — 앞 요청이 끝나지 않으면 풀리지 않는다. 응답 없는 요청이 실제로 보이면 시한을 넣는다.
  - 오류: 422 는 칸 옆에 `errors`(알림 없음), 401 은 「로그인이 끝났습니다」와 `/login?next=<지금 주소>` 링크(자동으로 옮기지 않는다 — 입력이 남는다), 403 「권한이 없습니다」, 429 「잠시 뒤 다시 시도하세요」, 그 밖 「저장하지 못했습니다」. 모두 `role="alert"`.
- **알림 `shared/ui/notice.tsx`** (FR-724): 화면 위쪽에 늘 그려 둔 `role="status"` 칸에 성공 문구 한 줄을 넣는다(빈 칸을 미리 그려야 화면 낭독기가 읽는다). 문구는 프런트 상수라 서버 값이 섞이지 않는다. 다음 제출을 시작하면 지우고, 다른 화면으로 가거나 새로 고치면 없다(세션을 쓰지 않는다, Q3).
- **확인 대화상자(FR-725)는 1-B2 로 미룬다.** 1-B1 에는 파괴적 동작이 없다(A-201 저장은 되돌릴 수 있다). 쓰는 곳 없이 만들면 흐름 시험이 없고, 1-B2 에서 같은 대화상자에 재인증 비밀번호 칸을 그리므로(Q16) 그때 한 번에 만든다.
- 사이트 설정: 이름 칸 하나(설치 폼과 같은 zod 규칙: `trim`, 1~100자). 처음 값은 `/api/site`. `can['settings.update']` 가 `false` 면 폼 대신 「권한이 없습니다」. 저장 뒤 알림 「사이트 설정을 저장했습니다」, `site` 쿼리를 무효화한다.
- 작업 로그: 표(시각·주체·동작·대상·요약·IP). 주체가 `''` 면 「시스템」, `actor_user_id` 가 NULL 이고 이메일이 있으면 「(삭제된 계정) 이메일」. 동작은 이름표(`auth.login` → 「로그인」, `settings.update` → 「사이트 설정 변경」), 모르는 키는 그대로. 시각은 브라우저 시간대. 「이전·다음」은 주소의 `?page=` 로(뒤로 가기가 된다).
- 시스템 정보: 버전, DB 서버 판, 마이그레이션 표(버전·설명·적용 시각·성공), 대기 수.

## 7. 다른 문서에서 고친 것

- 1-A 스펙(「1-B1 고침」 표시): 로그인 성공을 작업 로그에도 남긴다(3.1), IP 는 클라이언트 IP 함수(4절), 표시 셋과 관리자 경로 규칙(5절 세 곳), `GET /api/admin` 은 1-B 도 `{}`(6절).
- 1-A 스펙에서 **1-B2 때 고칠 것**: 로그인 성공 때 `reauth_at` 도 찍는다(3.1), 세션 속성 셋·셋째는 선택 속성이라 없거나 타입이 달라도 세션을 버리지 않는다(3.3 두 곳과 저장 문장), 403 에 재인증 필요를 더하고 전용 `type` 으로 가른다(6절).
- `docs/decisions.md`: FR-720 배정 수정, 「1-B 로 넘기는 설계 문제」 해결, 새 절 「Phase 1-B 결정」.

## 8. 검증

- 통합 시험(Testcontainers PostgreSQL). `operation_logs` 는 시험도 비울 수 없으므로 「표가 비어 있다」가 아니라 **계정별·증분**으로 단언한다.
  - **첫 Red(Q1)**: 시험용 역할에 `settings.view` 만 준 사용자(`admin.access` 없음)가 `@AdminPermission(SETTINGS_VIEW)` 처리기에서 403. `admin.access` 만 가진 사용자(`editor`)도 403. 관리자 경로에 `@RequiresPermission(SETTINGS_VIEW)` 를 단 시험 컨트롤러를 넣으면 기동이 실제로 멈춘다.
  - 기동 점검: `@AdminPermission` 과 `@RequiresPermission` 이 함께 붙으면 멈춘다, 관리자 경로 `@PublicApi` 는 여전히 멈춘다, `@AdminPermission` 의 값은 「미사용 권한」 경고에서 빠진다.
  - V3: 권한 키 집합이 넷, `operator` 에 세 권한, `editor` 에는 없음. 앱의 행 UPDATE·DELETE·TRUNCATE 가 거부된다. `actor_user_id = NULL` 과 다른 칸을 함께 바꾸는 UPDATE 도 거부된다. `DELETE FROM users` 는 성공하고 그 행의 `actor_user_id` 가 NULL, `actor_email` 은 남으며, 그 뒤에도 행은 고칠 수 없다(D86 5.11).
  - 로그인 성공 → 그 계정의 `auth.login` 행 하나(주체·이메일·`user`/id·ip `127.0.0.1`). 로그인 실패 → 그 계정의 행이 늘지 않는다.
  - 사이트 이름 규칙(설치와 A-201 이 같은 함수·같은 zod 규칙): 앞뒤의 공백(`\p{Z}`·`\s`, NBSP 포함)을 빼고, 공백·보이지 않는 문자(`\p{Z}`·`\p{C}`, 영폭 공백 U+200B 포함)만 남으면 422 「필수입니다」. NBSP 만·영폭 공백만인 이름이 서버·프런트 모두에서 거부된다(설치 쪽도 같은 시험).
  - A-201: `operator`·`admin` 204, `editor` 403, 익명 401, CSRF 없음 403. 1·100자 통과, 101자·빈 값·공백만 422 `errors.name`. 앞뒤 공백을 뺀 값이 `/api/site` 에 보인다. 같은 값으로 저장하면 행이 늘지 않고, 바꾸면 `summary = 'site.name'` 행 하나. 본문의 `type` 은 무시된다(`site.type` 그대로).
  - 비밀 검사(4.3): 로그인·저장 뒤 그 계정의 행 전체에 비밀번호·세션 쿠키·XSRF 토큰·DB 비밀번호가 없다.
  - 기록 실패: 시험 DB 에 INSERT 를 거부하는 트리거를 걸면 로그인은 204, 저장은 204 이고 ERROR 로그가 남는다. 그 로그에 이메일·IP 가 없다.
  - A-601: `operator` 200, `editor` 403. 표에 이미 있는 행(시험 계정의 `auth.login` 등)을 먼저 센 뒤 총 행 수가 101 이 되게 채우면 1쪽 100행·2쪽 1행·`total` 101, 최신이 먼저. `page=0` 422, `page=abc` 400. 질의 수가 행 수와 관계없이 같다(1-A 「공개 API 는 질의하지 않는다」 시험과 같은 방법).
  - A-602: `operator` 200, `editor` 403. 적용 목록에 1·2·3 이 성공으로, `pending_count` 0, `server_version` 이 비지 않는다. 응답 본문에 JDBC URL·DB 호스트:포트·DB 계정·DB 비밀번호가 없다.
  - `/api/me` 의 `can`: `operator` 는 넷 다 `true`, `editor` 는 `admin.access` 만.
  - 바뀌는 기존 시험: `MigrationTest` 의 V2 시험은 `target("2")` 로 V2 시점을 본다, 실패 원복 시험의 기대 이력은 1·2·3. `OpenApiFileTest` 가 새 API 셋을 확인한다. 단언을 완화하지 않는다.
- 프런트 시험(Vitest): `can` 에 따라 메뉴 항목이 그려지거나 DOM 에 없다. 사이트 설정의 204 → `role="status"` 알림, 422 → 칸 오류·알림 없음, 401 → 안내와 링크·입력 유지, 403·429 문구. 빠른 두 번 누름에 요청 하나, 「처리 중…」, 끝나면(성공·실패) 풀림. 성공 문구 없는 `useSave` 호출은 `@ts-expect-error` 로 타입 실패를 확인한다. 다른 화면에 갔다 오면 알림이 없다. 작업 로그의 「시스템」·「(삭제된 계정)」 표시와 쪽 이동. 시스템 정보 표시.
- E2E(Playwright, 실제 jar): 설치 → 로그인 → `/admin` 에 메뉴 셋 → 사이트 이름 저장 → 알림 → 홈에 새 이름 → 작업 로그에 `auth.login`·`settings.update` → 시스템 정보에 V3. 저장 단추 두 번 누름에 POST 하나(원문 W6-48). 키보드만으로 메뉴 이동과 저장 한 번.
- 메모리: 1-B 를 다 마친 뒤(1-B2 뒤) 코어 jar 를 `TARGET=core` 로 다시 잰다(1-A 와 같은 방법). 1-B1 만으로는 재지 않는다.

## 9. D86 대조 — 1-B1 행 (D86 9절 4항: 화면마다 행을 먼저 쓴다)

| D86 | 1-B1 에서 |
|---|---|
| 1.12 프록시 뒤 IP | 미해결 — Phase 4 배포 전 필수. 이제 작업 로그 `ip` 도 같은 한계다. 고칠 곳은 `ClientIp` 하나 |
| 2.1 처리기 권한·기동 점검 | `@AdminPermission` + 관리자 경로 표시 규칙(3절), 첫 Red 셋 |
| 2.5 대량 할당 | A-201 은 고정 레코드 `name` 하나 |
| 2.8 관리자 60/분 | 새 API 셋 모두 `/api/admin/**` — 1-A 필터 그대로 |
| 3.1 CSRF | A-201 POST — 1-A 그대로 |
| 3.10 오류가 내부를 말하지 않는다 | 새 API 의 오류도 Problem Details |
| 4.15 A-201 저장이 작업 로그에 남고 D19 대로 검사 | 바뀐 키 이름만, 바뀐 것이 없으면 없음, 1~100자 |
| 5.10 시크릿이 로그·화면에 없음 | 기록기 칸 출처 고정 + 비밀 검사 시험, A-602 에 DSN 없음 |
| 5.11 append-only | 트리거 넷, SET NULL 만 통과 |
| 6.6 로그에 DSN·비밀번호 없음 | A-602 는 고정 칸만, 기록 실패 ERROR 로그에 값을 넣지 않는다 |
| 7.3 목록 질의 한 번 | A-601 은 목록·셈 둘, 행 수와 무관(이메일은 스냅샷이라 조인이 없다) |
| 7.13·7.16 셈·무한 증가 | A-601 은 센다. 지우지 않는다(설계 성질), `LIMIT 100` + `created_at` 인덱스 |
| 1.11·1.13·2.3·2.4·2.4a·2.7·2.10 | 1-B2 |

## 10. 위험과 확인할 것

| 위험 | 대응 |
|---|---|
| 기록기나 A-602 가 설치 컨텍스트(DataSource 없음)를 깬다 | 운영 컨텍스트에만 둔다. `LauncherTest`·설치 E2E |
| `@AdminPermission` 의 SpEL `and` 템플릿이 판에서 기대대로 채워지지 않는다 | 첫 Red 셋이 먼저 잡는다 |
| 작업 로그 실패가 조용히 행을 잃는다 | ERROR 로그. 돈 작업 전에 같은 트랜잭션 기록을 다시 본다(4.1) |
| 로그인 성공 행이 계정 수 × 분당 5회로 계속 쌓인다 | 지금은 계정을 관리자가 만든다. 1-E 에서 가입이 열리면 Q13 의 논리가 성공 행에도 닿으므로 그때 다시 본다 |
| CloudFront 뒤로 옮긴 뒤 Phase 4 전에 쌓인 `ip` 는 고칠 수 없다 | Phase 4 가 배포 전 필수다 |

## 11. 검토 초점 — 시험이 다루지 않지만 사람이 쓸 때 문제가 될 것

1. **보이지 않는 사이트 이름** — 설계 검토에서 시험으로 옮겼다(8절 「사이트 이름 규칙」). 남는 것: 보이는 글자 하나에 보이지 않는 글자를 많이 붙인 이름은 길이 상한(100)까지 받는다.
2. **IP 표기**: 같은 사용자가 IPv4 와 IPv4-mapped IPv6(`::ffff:…`)로 오면 횟수 제한 키와 작업 로그 `ip` 가 다르게 보인다. 영역 ID 가 붙은 주소는 NULL 로 남는다.
3. **쪽 밀림과 깊은 쪽**: 로그인이 이어지는 동안 쪽을 넘기면 앞쪽 행이 다음 쪽에 다시 보인다. 큰 `page` 는 OFFSET 이 표를 훑어 느리다.
4. **동시 저장**: 두 관리자가 이름을 거의 같이 바꾸면 둘 다 「저장했습니다」를 보고 나중 값이 이긴다. 먼저 저장한 쪽 화면은 덮인 것을 모른다(행은 둘 다 남는다).
5. **시각의 시간대**: A-601 은 브라우저 시간대로, 앱 로그는 서버 시간대(기본 UTC)로 찍힌다. 같은 사건을 대조할 때 시차만큼 어긋나 보인다.

## 12. 열린 문제

1. **Q12 버전 노출** — 사용자 확인 필요. 임시로 헬스는 그대로 버전을 공개하고 A-602 에도 보인다.
2. ~~A-602 의 「DB 연결 상태」~~ — 설계 검토에서 정했다: `connected` 칸을 두지 않고 `server_version` 으로 보인다(5절). 끊김 진단은 앱 로그로 한다.
3. **프런트 묶음 버전** — NFR-305 「두 버전」. 프런트 판을 어디서 읽을지 정한 바가 없어 A-602 는 jar 버전만 보인다.
4. **작업 로그를 일과 다른 트랜잭션에 쓴다** — 원문 코드와 같지만 감사 행이 빠질 수 있다. 돈 작업(Phase 3) 전에 정한다.
