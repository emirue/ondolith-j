package dev.ondolithj.config;

/** 설정 파일을 읽은 결과. 없음이면 설치 모드, 깨짐이면 기동 거부, 읽힘이면 운영 모드다. */
public sealed interface ConfigLoad {

	record Missing() implements ConfigLoad {
	}

	record Broken(String reason) implements ConfigLoad {
	}

	record Loaded(ConfigFile config) implements ConfigLoad {
	}
}
