// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.constant;

import java.util.Arrays;

public enum AlcoholType {
	ALL(0),
	WHISKY(1),
	WINE(2),
	SET(11);

	private final int code;

	AlcoholType(int code) {
		this.code = code;
	}

	public int getCode() {
		return code;
	}

	public static AlcoholType from(Integer code) {
		return Arrays.stream(values())
			.filter(type -> code != null && type.code == code)
			.findFirst()
			.orElse(ALL);
	}
}
