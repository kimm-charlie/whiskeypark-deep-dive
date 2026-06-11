package be.weskey.module.member.file.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import be.weskey.module.member.file.dto.request.FileDeleteRequest;
import be.weskey.module.member.file.dto.request.FileUploadRequest;
import be.weskey.module.member.file.dto.response.FileUploadResponse;
import be.weskey.module.member.file.facade.FileFacade;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class FileController {

	private final FileFacade fileFacade;

	@PostMapping("/files/presigned-url")
	public ResponseEntity<FileUploadResponse> createPresignedUrl(
		@AuthenticationPrincipal Long memberId,
		@RequestBody @Validated FileUploadRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(fileFacade.createPresignedUrl(memberId, request));
	}

	@DeleteMapping("/files")
	public ResponseEntity<Void> deleteByIds(
		@AuthenticationPrincipal Long memberId,
		@RequestBody @Validated FileDeleteRequest request) {
		fileFacade.deleteByIds(memberId, request);
		return ResponseEntity.noContent().build();
	}
}
