// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.aws.enums;

import java.util.Arrays;

public enum S3DirectoryType {
	PROFILE("profile/"),
	TASTING_NOTE("tasting-note/"),
	COMMUNITY_ARTICLE("community-article/"),
	SUGGEST("suggest/"),
	ETC("etc/");

	private final String directoryName;

	S3DirectoryType(String directoryName) {
		this.directoryName = directoryName;
	}

	public String getDirectoryName() {
		return directoryName;
	}

	public static S3DirectoryType findByName(String name) {
		return Arrays.stream(values())
			.filter(type -> type.name().equalsIgnoreCase(name))
			.findFirst()
			.orElse(ETC);
	}
}
