package be.weskey.exception.exceptions;

import org.springframework.http.HttpStatus;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum PaymentCancelOutboxException implements CustomException {

	NOT_FOUND(HttpStatus.NOT_FOUND, "결제 취소 outbox 를 찾을 수 없습니다.", "PAYMENT_CANCEL_OUTBOX_001");

	private final HttpStatus status;
	private final String message;
	private final String code;

	@Override
	public HttpStatus getHttpStatus() {
		return status;
	}

	@Override
	public String getErrorMessage() {
		return message;
	}

	@Override
	public String getName() {
		return name();
	}

	@Override
	public String getCode() {
		return code;
	}
}
