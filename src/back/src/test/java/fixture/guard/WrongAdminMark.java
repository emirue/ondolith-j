package fixture.guard;

import dev.ondolithj.auth.Permission;
import dev.ondolithj.auth.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 경로에 admin.access 가 빠진 표시 — 기동 점검이 막아야 한다. */
@RestController
public class WrongAdminMark {

	@RequiresPermission(Permission.SETTINGS_VIEW)
	@GetMapping("/api/admin/wrong")
	public String wrong() {
		return "";
	}
}
