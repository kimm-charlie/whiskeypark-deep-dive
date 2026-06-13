// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.store_stock.entity;

import java.util.Arrays;

public enum OrderType {
	NEWEST(1),
	POPULARITY(2),
	HIGH_TO_LOW(3),
	LOW_TO_HIGH(4);

	private final int code;

	OrderType(int code) {
		this.code = code;
	}

	public static OrderType from(Integer code) {
		return Arrays.stream(values())
			.filter(type -> code != null && type.code == code)
			.findFirst()
			.orElse(NEWEST);
	}
}
