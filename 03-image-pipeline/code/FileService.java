package be.weskey.module.member.file.service;

import static be.weskey.shared.cdn.ImageUrlConverter.*;

import java.util.List;

import org.springframework.stereotype.Service;

import be.weskey.exception.CustomRuntimeException;
import be.weskey.exception.exceptions.FileException;
import be.weskey.module.member.community_article_image.entity.CommunityArticleImage;
import be.weskey.module.member.community_article_image.service.CommunityArticleImageCommandService;
import be.weskey.module.member.community_article_image.service.CommunityArticleImageQueryService;
import be.weskey.module.member.file.dto.request.FileDeleteRequest;
import be.weskey.module.member.file.dto.request.FileUploadRequest;
import be.weskey.module.member.file.dto.response.FileUploadResponse;
import be.weskey.module.member.file.validator.FileValidator;
import be.weskey.module.member.member.entity.Member;
import be.weskey.module.member.member.service.MemberQueryService;
import be.weskey.module.member.member_image.entity.MemberImage;
import be.weskey.module.member.member_image.repository.MemberImageRepository;
import be.weskey.module.member.suggest_image.entity.SuggestImage;
import be.weskey.module.member.suggest_image.service.SuggestImageCommandService;
import be.weskey.module.member.suggest_image.service.SuggestImageQueryService;
import be.weskey.module.member.tasting_note_image.entity.TastingNoteImage;
import be.weskey.module.member.tasting_note_image.service.TastingNoteImageCommandService;
import be.weskey.module.member.tasting_note_image.service.TastingNoteImageQueryService;
import be.weskey.shared.aws.dto.GeneratedPresignedUrl;
import be.weskey.shared.aws.enums.S3DirectoryType;
import be.weskey.shared.aws.service.S3PresignedUrlService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FileService {

	private final MemberImageRepository memberImageRepository;
	private final MemberQueryService memberQueryService;
	private final S3PresignedUrlService s3PresignedUrlService;
	private final FileValidator fileValidator;
	private final TastingNoteImageCommandService tastingNoteImageCommandService;
	private final TastingNoteImageQueryService tastingNoteImageQueryService;
	private final CommunityArticleImageCommandService communityArticleImageCommandService;
	private final CommunityArticleImageQueryService communityArticleImageQueryService;
	private final SuggestImageCommandService suggestImageCommandService;
	private final SuggestImageQueryService suggestImageQueryService;

	public FileUploadResponse createPresignedUrl(Long memberId, FileUploadRequest request) {
		S3DirectoryType directoryType = S3DirectoryType.findByName(request.getDirectoryType());

		GeneratedPresignedUrl presignedUrl = s3PresignedUrlService.generatePresignedUrl(
			directoryType, request.getFileExtension(), request.getFileSize());

		String cdnUrl = toCdnUrl(presignedUrl.getOriginUrl());
		Member member = memberQueryService.findById(memberId);

		Long fileSize = request.getFileSize();

		return switch (directoryType) {
			case PROFILE -> saveProfileFile(presignedUrl, memberId, fileSize);
			case TASTING_NOTE -> saveTastingNoteFile(presignedUrl, cdnUrl, fileSize, member);
			case COMMUNITY_ARTICLE -> saveCommunityArticleFile(presignedUrl, cdnUrl, fileSize, member);
			case SUGGEST -> saveSuggestFile(presignedUrl, cdnUrl, fileSize, member);
			default -> throw new CustomRuntimeException(FileException.INVALID_DIRECTORY_TYPE);
		};
	}

	public void deleteByIds(Long memberId, FileDeleteRequest request) {
		S3DirectoryType directoryType = S3DirectoryType.findByName(request.getDirectoryType());

		switch (directoryType) {
			case PROFILE -> request.getFileIds().forEach(fileId -> deleteProfileFile(fileId, memberId));
			case TASTING_NOTE -> deleteTastingNoteFiles(request.getFileIds(), memberId);
			case COMMUNITY_ARTICLE -> deleteCommunityArticleFiles(request.getFileIds(), memberId);
			case SUGGEST -> deleteSuggestFiles(request.getFileIds(), memberId);
			default -> throw new CustomRuntimeException(FileException.INVALID_DIRECTORY_TYPE);
		}
	}

	// --- PROFILE ---

	private FileUploadResponse saveProfileFile(GeneratedPresignedUrl presignedUrl, Long memberId, Long fileSize) {
		Member member = memberQueryService.findById(memberId);
		String cdnUrl = toCdnUrl(presignedUrl.getOriginUrl());
		MemberImage newImage = MemberImage.ofPresigned(cdnUrl, presignedUrl.getOriginUrl(), presignedUrl.getFileKey(), fileSize, member);
		MemberImage savedImage = memberImageRepository.save(newImage);
		return FileUploadResponse.of(presignedUrl.getPresignedUrl(), savedImage.getId(), cdnUrl);
	}

	private void deleteProfileFile(Long fileId, Long memberId) {
		MemberImage memberImage = memberImageRepository.findById(fileId)
			.orElseThrow(() -> new CustomRuntimeException(FileException.NOT_FOUND));
		fileValidator.validateFileOwnership(memberImage.isOwnedBy(memberId));
		memberImage.softDelete();
	}

	// --- TASTING_NOTE ---

	private FileUploadResponse saveTastingNoteFile(GeneratedPresignedUrl presignedUrl, String cdnUrl,
		Long fileSize, Member member) {
		TastingNoteImage image = tastingNoteImageCommandService.save(
			TastingNoteImage.ofPresigned(cdnUrl, presignedUrl.getOriginUrl(),
				presignedUrl.getFileKey(), fileSize, member));
		return FileUploadResponse.of(presignedUrl.getPresignedUrl(), image.getId(), cdnUrl);
	}

	private void deleteTastingNoteFiles(List<Long> fileIds, Long memberId) {
		List<TastingNoteImage> images = tastingNoteImageQueryService.findAllActiveByIds(fileIds);
		fileValidator.validateImageCountAndOwnership(images, fileIds, memberId);
		tastingNoteImageCommandService.softDeleteAllByIds(fileIds);
	}

	// --- COMMUNITY_ARTICLE ---

	private FileUploadResponse saveCommunityArticleFile(GeneratedPresignedUrl presignedUrl, String cdnUrl,
		Long fileSize, Member member) {
		CommunityArticleImage image = communityArticleImageCommandService.save(
			CommunityArticleImage.ofPresigned(cdnUrl, presignedUrl.getOriginUrl(),
				presignedUrl.getFileKey(), fileSize, member));
		return FileUploadResponse.of(presignedUrl.getPresignedUrl(), image.getId(), cdnUrl);
	}

	private void deleteCommunityArticleFiles(List<Long> fileIds, Long memberId) {
		List<CommunityArticleImage> images = communityArticleImageQueryService.findAllActiveByIds(fileIds);
		fileValidator.validateImageCountAndOwnership(images, fileIds, memberId);
		communityArticleImageCommandService.softDeleteAllByIds(fileIds);
	}

	// --- SUGGEST ---

	private FileUploadResponse saveSuggestFile(GeneratedPresignedUrl presignedUrl, String cdnUrl,
		Long fileSize, Member member) {
		SuggestImage image = suggestImageCommandService.save(
			SuggestImage.ofPresigned(cdnUrl, presignedUrl.getOriginUrl(),
				presignedUrl.getFileKey(), fileSize, member));
		return FileUploadResponse.of(presignedUrl.getPresignedUrl(), image.getId(), cdnUrl);
	}

	private void deleteSuggestFiles(List<Long> fileIds, Long memberId) {
		List<SuggestImage> images = suggestImageQueryService.findAllActiveByIds(fileIds);
		fileValidator.validateImageCountAndOwnership(images, fileIds, memberId);
		suggestImageCommandService.softDeleteAllByIds(fileIds);
	}
}
