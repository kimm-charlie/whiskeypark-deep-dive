// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.exception;

public class CustomRuntimeException extends RuntimeException {

	public CustomRuntimeException(Enum<?> exceptionType) {
		super(exceptionType.name());
	}
}
