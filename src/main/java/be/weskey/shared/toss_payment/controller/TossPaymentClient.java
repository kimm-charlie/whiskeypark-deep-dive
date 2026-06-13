// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.toss_payment.controller;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import be.weskey.shared.toss_payment.dto.response.TossPaymentTransactionResponse;
import feign.Request;

@FeignClient(name = "tossPaymentClient", url = "https://api.tosspayments.com")
public interface TossPaymentClient {

	@GetMapping("/v1/transactions")
	ResponseEntity<List<TossPaymentTransactionResponse>> getTransactions(
		@RequestParam("startDate") String startDate,
		@RequestParam("endDate") String endDate,
		@RequestParam(value = "startingAfter", required = false) String startingAfter,
		@RequestParam("limit") int limit,
		@RequestHeader("Authorization") String authorization,
		Request.Options options);
}
