package dev.ondolithj.shared;

/** 지금 떠 있는 컨텍스트: "install" 또는 "running". 각 루트 설정이 빈으로 둔다. */
public record AppState(String state) {
}
