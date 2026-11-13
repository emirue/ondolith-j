package dev.ondolithj.install;

import java.nio.file.Path;

/** 설치가 쓸 설정 파일 경로. Launcher 가 설치 컨텍스트에 넣는다. */
public record InstallTarget(Path configFile) {
}
