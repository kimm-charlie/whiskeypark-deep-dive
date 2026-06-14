package be.weskey.shared.toss_payment.service;

import java.util.List;

import org.springframework.stereotype.Service;

import be.weskey.exception.CustomRuntimeException;
import be.weskey.exception.exceptions.PaymentCancelOutboxException;
import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;
import be.weskey.shared.toss_payment.repository.PaymentCancelOutboxRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentCancelOutboxQueryService {

	private final PaymentCancelOutboxRepository paymentCancelOutboxRepository;

	public List<Long> findPendingIds() {
		return paymentCancelOutboxRepository.findPendingIds();
	}

	public List<PaymentCancelOutbox> findActiveWithoutPaymentInfo() {
		return paymentCancelOutboxRepository.findActiveWithoutPaymentInfo();
	}

	public List<PaymentCancelOutbox> findActiveByPaymentInfoIds(List<Long> paymentInfoIds) {
		return paymentCancelOutboxRepository.findActiveByPaymentInfoIds(paymentInfoIds);
	}

	public PaymentCancelOutbox findById(Long id) {
		return paymentCancelOutboxRepository.findById(id)
			.orElseThrow(() -> new CustomRuntimeException(PaymentCancelOutboxException.NOT_FOUND));
	}

	public boolean existsByPaymentKey(String paymentKey) {
		return paymentCancelOutboxRepository.existsByPaymentKey(paymentKey);
	}
}
