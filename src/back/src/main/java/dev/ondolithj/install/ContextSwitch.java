package dev.ondolithj.install;

import dev.ondolithj.config.ConfigFile;

/** 설치가 끝났을 때 운영 컨텍스트로 바꿔 달라고 Launcher 에 알린다(스펙 2.3). */
@FunctionalInterface
public interface ContextSwitch {

	void requestRunning(ConfigFile config);
}
