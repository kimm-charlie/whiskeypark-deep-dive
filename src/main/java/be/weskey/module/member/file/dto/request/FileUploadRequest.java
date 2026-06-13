// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.file.dto.request;

import lombok.Getter;

@Getter
public class FileUploadRequest {

	private String directoryType;
	private String fileExtension;
	private Long fileSize;
}
