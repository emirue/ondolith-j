코어 프런트(React SPA). 운영은 S3 + CloudFront 로 배포한다(D-3).

```bash
mise install --locked java node     # 저장소 루트에서 한 번
npm ci                              # 이 폴더에서
npm run dev                         # Vite. /api 는 localhost:8080(jar)으로 넘긴다
npm test && npm run typecheck
npm run gen:api                     # openapi.json → src/shared/api/schema.d.ts
```

- `openapi.json` 은 백엔드 시험(`OpenApiFileTest`)이 쓴다. 손으로 고치지 않는다.
- UI 컴포넌트는 shadcn/ui 를 `src/shared/ui` 에 복사해 쓴다(`npx shadcn add <이름>`).
