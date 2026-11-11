# ondolith-j

[ondolith](https://github.com/emirue/ondolith)(Go) 의 기능을 백엔드 Spring Boot(API) 와 프런트 React(SPA) 로 다시 만드는 **공개 코어**다. ondolith 와는 분리된 저장소다.

| 구분 | 스택 | 배포 |
|---|---|---|
| 백엔드 | Spring Boot 4.1.2, Java 25, Gradle Kotlin, PostgreSQL 18 | Lightsail |
| 프런트 | React, Zustand, TanStack Query, Tailwind CSS | S3 + CloudFront |

## 상태

설계 전. 결정 항목(테마, 검색 노출, 배포 단위, Lightsail 티어)을 정한 뒤 스펙 → 계획 → Phase 0(설치·뼈대·CI·보안 기본값) 순서로 만든다.

## 문서

- [`docs/request-core.md`](docs/request-core.md): 요청서(범위·결정 항목·보안 요구)
- [`docs/measure-lightsail.md`](docs/measure-lightsail.md): Spring Boot + PostgreSQL 을 Lightsail 512MB/1GB 에 올릴 수 있는지 잰 메모리 실측 결과
