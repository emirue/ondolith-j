package dev.ondolithj.admin;

import dev.ondolithj.auth.Permission;
import dev.ondolithj.auth.RequiresPermission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 트리의 문(스펙 6절). 1-B 가 셸 내용을 채운다. /api/admin/** 는 모두 admin.access 를 요구한다. */
@RestController
@RequiresPermission(Permission.ADMIN_ACCESS)
class AdminController {

	@GetMapping("/api/admin")
	Map<String, Object> shell() {
		return Map.of();
	}
}
