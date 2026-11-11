# ondolith-j

[ondolith](https://github.com/emirue/ondolith)(Go) 의 기능을 백엔드 Spring Boot(API) 와 프런트 React(SPA) 로 다시 만드는 **공개 코어**다. ondolith 와는 분리된 저장소다.

| 구분 | 스택 | 배포 |
|---|---|---|
| 백엔드 | Spring Boot, Java, Gradle Kotlin, PostgreSQL | Lightsail |
| 프런트 | React, Zustand, TanStack Query, Tailwind CSS | S3 + CloudFront |

판은 모두 최신 안정판을 쓰고, 고를 때마다 확인한다.

## 상태

Phase 0(설치·뼈대·CI·보안 기본값) 스펙 검토 중. 결정은 [`docs/decisions.md`](docs/decisions.md), 스펙은 [`docs/superpowers/specs/`](docs/superpowers/specs/).

## 문서

- [`docs/request-core.md`](docs/request-core.md): 요청서(범위·결정 항목·보안 요구)
- [`docs/measure-lightsail.md`](docs/measure-lightsail.md): Spring Boot + PostgreSQL 을 Lightsail 512MB/1GB 에 올릴 수 있는지 잰 메모리 실측 결과
