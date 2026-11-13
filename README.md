# ondolith-j

[ondolith](https://github.com/emirue/ondolith)(Go) 의 기능을 백엔드 Spring Boot(API) 와 프런트 React(SPA) 로 다시 만드는 **공개 코어**다. ondolith 와는 분리된 저장소다.

| 구분 | 스택 | 배포 |
|---|---|---|
| 백엔드 | Spring Boot, Java, Maven, PostgreSQL | Lightsail |
| 프런트 | React, TanStack Query, React Hook Form + Zod, Tailwind CSS, shadcn/ui | S3 + CloudFront |

판은 모두 최신 안정판을 쓰고, 고를 때마다 확인한다.

## 상태

Phase 0(설치·뼈대·CI·보안 기본값) 구현 중. 결정은 [`docs/decisions.md`](docs/decisions.md), 스펙은 [`docs/superpowers/specs/`](docs/superpowers/specs/), 계획은 [`docs/superpowers/plans/`](docs/superpowers/plans/).

## 개발

도구 판은 [mise](https://mise.jdx.dev) 로 고정한다(Java 25, Node 26.13.0). Docker 가 있어야 시험이 돈다.

```bash
mise install --locked java node
mise run test     # 셸 + 백엔드(Testcontainers) + 프런트 시험
mise run e2e      # 실제 jar 로 설치 → 대기 → 홈 브라우저 흐름
mise run db       # 개발용 PostgreSQL(127.0.0.1:5432, 가짜 계정 dev/dev)
```

로컬 실행: `src/back` 에서 `./mvnw spring-boot:run`(설정 파일이 없으면 설치 모드), `src/front` 에서 `npm run dev` 를 띄우고 Vite 주소로 들어간다.

## 설치할 때 주의

**설치가 끝날 때까지 방화벽으로 서버 포트를 막아 두고 설치한다.** 설치 전에는 포트에 닿는 누구나 설치 API 를 부를 수 있다(설치가 끝나면 설치 API 는 닫힌다). 설정 파일(`ondolith-j.properties`)은 권한 0600 으로 만들어지고 DB 비밀번호와 봉인 키가 들어 있으니 백업 때도 그대로 지킨다.

## 문서

- [`docs/request-core.md`](docs/request-core.md): 요청서(범위·결정 항목·보안 요구)
- [`docs/measure-lightsail.md`](docs/measure-lightsail.md): Spring Boot + PostgreSQL 을 Lightsail 512MB/1GB 에 올릴 수 있는지 잰 메모리 실측 결과
