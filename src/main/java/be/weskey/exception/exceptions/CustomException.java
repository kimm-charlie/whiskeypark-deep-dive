package be.weskey.exception.exceptions;

import org.springframework.http.HttpStatus;

public interface CustomException {
	HttpStatus getHttpStatus();

	String getErrorMessage();

	String getName();

	String getCode();
}
