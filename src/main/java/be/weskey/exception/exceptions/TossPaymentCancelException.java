package be.weskey.exception.exceptions;

import org.springframework.http.HttpStatus;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum TossPaymentCancelException implements CustomException {

	ALREADY_CANCELED_PAYMENT(HttpStatus.BAD_REQUEST, "이미 취소된 결제입니다.", "TOSS_PAYMENT_CANCEL_001"),
	PAYMENT_CANCEL_FAIL(HttpStatus.BAD_REQUEST, "결제 취소요청에 실패했습니다.", "TOSS_PAYMENT_CANCEL_002");

	private final HttpStatus status;
	private final String message;
	private final String code;

	public static CustomException from(String code, String message) {
		for (TossPaymentCancelException exception : TossPaymentCancelException.values()) {
			if (exception.name().equals(code)) {
				return exception;
			}
		}
		return TossPaymentCancelException.PAYMENT_CANCEL_FAIL;
	}

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
