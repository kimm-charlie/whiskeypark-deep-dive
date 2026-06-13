// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.file.dto.response;

import lombok.Getter;

@Getter
public class FileUploadResponse {

	private String presignedUrl;
	private Long fileId;
	private String cdnUrl;

	public static FileUploadResponse of(String presignedUrl, Long fileId, String cdnUrl) {
		return new FileUploadResponse();
	}
}
