# 03. 이미지 처리 비용 약 90% 절감 — Presigned URL + Lambda WebP 변환

> 문제 배경·버킷 분리 판단·검증(Cost Explorer)은 deep-dive 자료에 정리했습니다.
> 이 README는 **그 설계가 코드의 어디에 있는지**만 안내합니다.

## 한 줄 요약

API 서버가 multipart 본문을 직접 받던 구조를 **클라이언트 → S3 직접 업로드(Presigned URL)**로 전환하고,
S3 PUT 이벤트로 **Lambda가 WebP 변환** → CloudFront 전송 비용 3월 대비 5월 약 **90% 감소**.
API 서버는 업로드 권한 발급·메타데이터만 담당.

## 흐름

```
클라이언트 → (1) Presigned URL 요청 → API 서버(권한 발급만)
         → (2) origin 버킷에 본문 직접 업로드 → (3) S3 PUT 이벤트 → Lambda(WebP 변환)
         → public 버킷 → CloudFront CDN
```

> Lambda(WebP 변환)는 별도 함수라 이 레포에는 없습니다. 여기엔 **API 서버 측(발급/메타데이터)** 코드만 발췌.

## 코드 맵 (설계 포인트 → 파일)

| 설계 포인트 | 코드 |
|------------|------|
| Presigned URL 발급 API | [`FileController`](../src/main/java/be/weskey/module/member/file/controller/FileController.java) |
| 발급 + 파일 메타데이터 처리 | [`FileFacade`](../src/main/java/be/weskey/module/member/file/facade/FileFacade.java) · [`FileService`](../src/main/java/be/weskey/module/member/file/service/FileService.java) |
| origin 업로드용 Presigned URL 발급 + public WebP URL 생성 | [`S3PresignedUrlService`](../src/main/java/be/weskey/shared/aws/service/S3PresignedUrlService.java) · [`GeneratedPresignedUrl`](../src/main/java/be/weskey/shared/aws/dto/GeneratedPresignedUrl.java) |
| 버킷/디렉토리 타입 · CDN URL 변환 | [`S3DirectoryType`](../src/main/java/be/weskey/shared/aws/enums/S3DirectoryType.java) · [`ImageUrlConverter`](../src/main/java/be/weskey/shared/cdn/ImageUrlConverter.java) |

CloudFront 비용 전후 추이: [cloudfront-cost-trend.png](./assets/cloudfront-cost-trend.png)
