package be.weskey.shared.aws.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import be.weskey.exception.CustomRuntimeException;
import be.weskey.exception.exceptions.FileException;
import be.weskey.shared.aws.dto.GeneratedPresignedUrl;
import be.weskey.shared.aws.enums.S3DirectoryType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3PresignedUrlService {

	private static final Duration PRESIGNED_URL_EXPIRATION = Duration.ofMinutes(10);

	private final S3Presigner s3Presigner;
	@Value("${aws.s3.bucket}")
	private String publicBucket;
	@Value("${aws.s3.origin}")
	private String originBucket;
	@Value("${spring.cloud.aws.region.static}")
	private String region;

	public GeneratedPresignedUrl generatePresignedUrl(S3DirectoryType s3DirectoryType,
		String fileExtension, long fileSizeBytes) {
		String uniqueFileName = UUID.randomUUID() + "." + fileExtension;
		String objectKey = s3DirectoryType.getDirectoryName() + uniqueFileName;
		boolean isPublicBucket = true;

		String contentType = resolveContentType(fileExtension);

		try {
			PutObjectRequest putObjectRequest = PutObjectRequest.builder()
				.bucket(originBucket)
				.key(objectKey)
				.contentType(contentType)
				.contentLength(fileSizeBytes)
				.build();

			PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
				.signatureDuration(PRESIGNED_URL_EXPIRATION)
				.putObjectRequest(putObjectRequest)
				.build();

			PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
			String presignedUrl = presignedRequest.url().toString();
			String originUrl = buildFileUrl(objectKey);

			return GeneratedPresignedUrl.of(presignedUrl, originUrl, objectKey, isPublicBucket);
		} catch (Exception e) {
			log.error("Failed to generate presigned URL for file: {} in directory: {}",
				uniqueFileName, s3DirectoryType.name(), e);
			throw new CustomRuntimeException(FileException.PRESIGNED_URL_GENERATION_FAILED);
		}
	}

	private String buildFileUrl(String objectKey) {
		String webpKey = objectKey.replaceAll("\\.[^.]+$", ".webp");
		return String.format("https://%s.s3.%s.amazonaws.com/%s", publicBucket, region, webpKey);
	}

	private String resolveContentType(String fileExtension) {
		return switch (fileExtension.toLowerCase()) {
			case "png" -> "image/png";
			case "jpg", "jpeg", "jfif" -> "image/jpeg";
			case "webp" -> "image/webp";
			case "gif" -> "image/gif";
			case "bmp" -> "image/bmp";
			case "svg" -> "image/svg+xml";
			default -> "application/octet-stream";
		};
	}
}
