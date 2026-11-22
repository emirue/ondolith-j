package dev.ondolithj.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ondolithj.auth.Actor;
import dev.ondolithj.auth.Permission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** R2·R4 의 판정 함수(1-B2 스펙 6절). superuser 역할의 권한 집합은 「코드의 권한 전부」다 — 빈 집합으로 통과하면 안 된다. */
class CoversTest {

	static Actor actor(boolean superuser, Permission... perms) {
		Set<Permission> set = perms.length == 0 ? Set.of() : EnumSet.of(perms[0], perms);
		return new Actor(UUID.randomUUID(), "covers@ondolith.test", "", Instant.EPOCH, superuser, set);
	}

	@Test
	void superuser_역할의_권한은_전부라_비superuser_는_덮지_못한다() {
		Actor a = actor(false, Permission.ADMIN_ACCESS, Permission.USER_VIEW);
		assertThat(RolesController.permissionsOf(true, List.of())).isEqualTo(EnumSet.allOf(Permission.class));
		assertThat(RolesController.covers(a, RolesController.permissionsOf(true, List.of()))).isFalse();
	}

	@Test
	void superuser_요청자는_전부를_덮는다() {
		assertThat(RolesController.covers(actor(true), RolesController.permissionsOf(true, List.of()))).isTrue();
	}

	@Test
	void 비superuser_는_가진_권한만_덮는다() {
		Actor a = actor(false, Permission.ADMIN_ACCESS, Permission.USER_VIEW);
		assertThat(RolesController.covers(a, RolesController.permissionsOf(false, List.of("admin.access", "user.view"))))
			.isTrue();
		assertThat(RolesController.covers(a, RolesController.permissionsOf(false, List.of("admin.access", "user.update"))))
			.isFalse();
	}

	@Test
	void 코드에_없는_키는_버리지_않고_던진다() {
		// 버리면 필요한 집합이 줄어 열린 쪽으로 실패한다
		assertThatThrownBy(() -> RolesController.permissionsOf(false, List.of("ghost.key")))
			.isInstanceOf(IllegalStateException.class);
	}
}
