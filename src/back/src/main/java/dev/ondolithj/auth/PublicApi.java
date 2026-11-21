package dev.ondolithj.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/** 누구나 부를 수 있다. 표시 없는 API 는 기동 점검이 막으므로, 공개도 이렇게 적어야 한다. */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("permitAll()")
public @interface PublicApi {
}
