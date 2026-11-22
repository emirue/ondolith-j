package fixture.guard;

import dev.ondolithj.auth.Permission;
import dev.ondolithj.auth.RequiresPermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 재인증 대상 표에도 대상 아님 표에도 없는 관리자 POST. ReauthTargetsTest 가 잡아내는지 본다. UnclassifiedPostConfig 로만 들어간다. */
@RestController
public class UnclassifiedPost {

	@RequiresPermission(Permission.ADMIN_ACCESS)
	@PostMapping("/api/admin/unclassified-probe")
	public ResponseEntity<Void> probe() {
		return ResponseEntity.noContent().build();
	}
}
