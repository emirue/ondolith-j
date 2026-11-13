package dev.ondolithj.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** settings 표 한 행(key → value). 사이트 이름·유형은 site.name·site.type. */
@Entity
@Table(name = "settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Setting {

	@Id
	@Column(name = "key")
	private String key;

	@Column(name = "value", nullable = false)
	private String value;
}
