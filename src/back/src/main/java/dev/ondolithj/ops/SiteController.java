package dev.ondolithj.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/site — 사이트 이름·유형(운영 컨텍스트에만 있다). */
@RestController
class SiteController {

	record Site(String name, String type) {
	}

	private final SettingRepository settings;

	SiteController(SettingRepository settings) {
		this.settings = settings;
	}

	@GetMapping("/api/site")
	Site site() {
		return new Site(value("site.name"), value("site.type"));
	}

	private String value(String key) {
		return settings.findById(key).map(Setting::getValue).orElse("");
	}
}
