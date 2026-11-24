import { z } from "zod";

// 빌드 CSP(script-src 'self', 1-C1 스펙 8.3)에서 zod 4 의 JIT 가능 여부 탐지가 막혀 위반 보고를 남긴다(탐지는 잡혀서 기능은 돈다).
// 스키마를 만들 때 이 값을 읽으므로 main.tsx 의 첫 import 로 둔다. CSP 아래에서는 원래 JIT 가 꺼지므로 동작은 같다
z.config({ jitless: true });
