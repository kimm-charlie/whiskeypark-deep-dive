// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.aws.dto;

import lombok.Getter;

@Getter
public class GeneratedPresignedUrl {

	private String presignedUrl;
	private String originUrl;
	private String fileKey;
	private boolean publicBucket;

	public static GeneratedPresignedUrl of(String presignedUrl, String originUrl, String fileKey,
		boolean publicBucket) {
		return new GeneratedPresignedUrl();
	}
}
