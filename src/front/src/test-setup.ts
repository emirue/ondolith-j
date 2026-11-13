import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// globals 를 켜지 않았으므로 Testing Library 의 자동 정리가 돌지 않는다
afterEach(() => cleanup());
