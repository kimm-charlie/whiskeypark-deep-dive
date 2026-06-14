package be.weskey.exception;

import be.weskey.exception.exceptions.CustomException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class CustomRuntimeException extends RuntimeException {

	private final CustomException customException;

	@Override
	public String getMessage() {
		return customException.getErrorMessage();
	}
}
