// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.file.facade;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import be.weskey.module.member.file.dto.request.FileUploadRequest;
import be.weskey.module.member.file.dto.response.FileUploadResponse;
import be.weskey.module.member.file.service.FileService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FileFacade {

	private final FileService fileService;

	@Transactional
	public FileUploadResponse createPresignedUrl(Long memberId, FileUploadRequest request) {
		return fileService.createPresignedUrl(memberId, request);
	}
}
