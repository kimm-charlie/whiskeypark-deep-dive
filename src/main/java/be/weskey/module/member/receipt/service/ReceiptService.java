package be.weskey.module.member.receipt.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import be.weskey.module.member.member.entity.Member;
import be.weskey.module.member.member.service.MemberQueryService;
import be.weskey.module.member.member_mileage_history.service.MemberMileageHistoryService;
import be.weskey.module.member.pick_up.entity.PickUp;
import be.weskey.module.member.pick_up.service.PickUpCommandService;
import be.weskey.module.member.pick_up.service.PickUpQueryService;
import be.weskey.module.member.receipt.dto.request.ReceiptRequest;
import be.weskey.module.member.receipt.dto.request.ReceiptSaveRequest;
import be.weskey.module.member.receipt.entity.Receipt;
import be.weskey.module.member.receipt.validator.ReceiptValidator;
import be.weskey.module.member.recipient.entity.Recipient;
import be.weskey.module.member.recipient.service.RecipientCommandService;
import be.weskey.module.member.shopping_cart.service.ShoppingCartCommandService;
import be.weskey.module.member.store.entity.Store;
import be.weskey.module.member.store_stock.entity.StoreStock;
import be.weskey.module.member.store_stock.service.StoreStockQueryService;
import be.weskey.module.member.store_stock_receipt_mapping.dto.request.StoreStockReceiptMappingRequest;
import be.weskey.module.member.store_stock_receipt_mapping.entity.StoreStockReceiptMapping;
import be.weskey.module.member.store_stock_receipt_mapping.service.StoreStockReceiptCommandService;
import be.weskey.module.member.store_stock_receipt_mapping.service.StoreStockReceiptMappingQueryService;
import be.weskey.redis.util.RedisShoppingCartQuantityUtil;
import be.weskey.shared.toss_payment.entity.PaymentInfo;
import be.weskey.shared.toss_payment.service.PaymentInfoQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptService {

	private final ReceiptCommandService receiptCommandService;
	private final RecipientCommandService recipientCommandService;
	private final StoreStockReceiptCommandService storeStockReceiptCommandService;
	private final StoreStockQueryService storeStockQueryService;
	private final ReceiptQueryService receiptQueryService;
	private final PickUpCommandService pickUpCommandService;
	private final PickUpQueryService pickUpQueryService;
	private final ShoppingCartCommandService shoppingCartCommandService;
	private final RedisShoppingCartQuantityUtil redisShoppingCartQuantityUtil;
	private final StoreStockReceiptMappingQueryService storeStockReceiptMappingQueryService;
	private final MemberMileageHistoryService memberMileageHistoryService;
	private final MemberQueryService memberQueryService;
	private final ReceiptValidator receiptValidator;
	private final PaymentInfoQueryService paymentInfoQueryService;

	/**
	 * 결제 도메인의 자연 idempotency key 인 paymentKey 기반 사전 체크.
	 * 이미 처리된 paymentKey 면 기존 receiptId 를, 아니면 비어있는 값을 반환한다.
	 * 다른 회원의 paymentKey 로 멱등 응답이 흘러가지 않도록 소유권 검증 포함.
	 */
	public Optional<Long> findIdempotentReceiptId(Long memberId, String paymentKey) {
		Optional<PaymentInfo> existingPaymentInfo = paymentInfoQueryService.findByPaymentKey(paymentKey);
		if (existingPaymentInfo.isEmpty()) {
			return Optional.empty();
		}
		Receipt existingReceipt = receiptQueryService.findByPaymentInfoId(existingPaymentInfo.get().getId());
		receiptValidator.validateReceiptMember(memberId, existingReceipt);
		return Optional.of(existingReceipt.getId());
	}

	// 주문 준비(TX1): 주문서 저장 + 재고·마일리지 차감. 마일리지는 주문 생성 시점에 사용 확정한다.
	public Receipt prepareOrder(Member member, ReceiptSaveRequest receiptSaveRequest, long totalPrice,
		AtomicLong lockNanos) {

		// 1. 주문서 저장
		Receipt receipt = receiptCommandService.save(
			receiptSaveRequest.toReceiptEntity(member, totalPrice, receiptSaveRequest.getPaymentTotalPrice()));

		// 2. 락 점유 시간 측정 시작 — 비관락 선점 직전부터 트랜잭션 종료까지
		startLockHoldMeasurement(lockNanos);

		// 3. recipient 및 storeStockReceiptMapping 저장 (이 안에서 StoreStock 비관락 선점 + 재고 차감)
		saveRecipientsAndStoreStockMappings(receipt, receiptSaveRequest.getReceiptRequests(), false);

		// 4. 마일리지 차감 — 회원 비관락 선점 후 재검증해 동시 주문에도 잔액 정합성을 보장한다.
		if (receiptSaveRequest.getMileage() > 0) {
			Member lockedMember = memberQueryService.findByIdWithLock(member.getId());
			processMileageUsage(lockedMember, receiptSaveRequest.getReceiptRequests(), receiptSaveRequest.getMileage(),
				totalPrice);
		}

		return receipt;
	}

	// Toss 결제 승인(Facade) 후 TX2: receipt 에 결제정보 연결 + 장바구니 삭제.
	public void completeOrder(Long receiptId, Long memberId, PaymentInfo paymentInfo,
		ReceiptSaveRequest receiptSaveRequest) {
		Receipt receipt = receiptQueryService.findById(receiptId);
		receipt.updatedPaymentInfo(paymentInfo);

		deleteShoppingCart(memberId, receiptSaveRequest.getReceiptRequests());
	}

	/**
	 * 결제 실패 보상(TX3): TX1 에서 만든 주문 흔적을 되돌린다.
	 * 차감했던 재고를 복원하고 receipt + mapping + pickup + recipient 를 하드 삭제한다.
	 * TX1 에서 차감한 마일리지도 함께 환급한다.
	 * 멱등 race(payment_info UNIQUE 위반) 시에도 로컬 정리는 동일하며, Toss 취소 여부 판단은 Facade 가 담당한다.
	 */
	public void compensate(Long receiptId) {
		Receipt receipt = receiptQueryService.findById(receiptId);

		// 1. 재고 복원 — mapping 재조회(락) → storeStock 재조회(락) → 차감했던 수량만큼 복원
		List<StoreStockReceiptMapping> storeStockReceiptMappings = storeStockReceiptMappingQueryService.findAllByReceiptIdWithLock(
			receiptId);
		List<StoreStock> storeStocks = storeStockQueryService.findAllByIdWithLock(storeStockReceiptMappings.stream()
			.map(storeStockReceiptMapping -> storeStockReceiptMapping.getStoreStock().getId())
			.toList());
		storeStocks.forEach(storeStock -> storeStockReceiptMappings.stream()
			.filter(mapping -> mapping.getStoreStock().getId().equals(storeStock.getId()))
			.forEach(mapping -> storeStock.increaseQuantity(mapping.getQuantity())));

		// 2. 주문 흔적 하드 삭제 — mapping → pickup → recipient → receipt 순서로 삭제한다.
		List<PickUp> pickUps = pickUpQueryService.findAllByReceiptId(receiptId);
		List<Recipient> recipients = storeStockReceiptMappings.stream()
			.map(StoreStockReceiptMapping::getRecipient)
			.distinct()
			.toList();

		storeStockReceiptCommandService.deleteAll(storeStockReceiptMappings);
		pickUpCommandService.deleteAll(pickUps);
		recipientCommandService.deleteAll(recipients);
		refundMileageWhenCompensating(receipt, storeStockReceiptMappings);
		receiptCommandService.delete(receipt);

		log.warn("[RECEIPT_COMPENSATED_DELETE] 결제 실패 보상 — receiptId: {} 재고·마일리지 복원 및 주문 흔적 삭제 완료", receiptId);
	}

	// 예약(saveReservation) 흐름용: 마일리지 검증 + 차감을 단일 트랜잭션에서 함께 수행한다.
	private void processMileageUsage(Member member, List<ReceiptRequest> receiptRequests, Integer mileage,
		long totalPrice) {
		receiptValidator.validateMileageUsage(receiptRequests, mileage, member, totalPrice);
		deductMileage(member, receiptRequests, mileage);
	}

	// 마일리지 차감 + 사용 내역 저장. 사전 검증과 별개로 TX1 안에서 잠금 후 다시 검증한다.
	private void deductMileage(Member member, List<ReceiptRequest> receiptRequests, Integer mileage) {
		List<StoreStock> storeStocks = storeStockQueryService.findAllById(
			receiptRequests.stream()
				.flatMap(receiptRequest -> receiptRequest.getOrderStoreStocks().stream())
				.map(StoreStockReceiptMappingRequest::getId)
				.toList());

		String mostExpensiveProductName = storeStocks.stream()
			.max(Comparator.comparing(StoreStock::getPrice))
			.map(mapping -> mapping.getWhiskyProduct().getKoreanName())
			.orElse("");

		int storeStockCount = receiptRequests.stream()
			.mapToInt(receiptRequest -> receiptRequest.getOrderStoreStocks().size())
			.sum();

		member.awardMileage(mileage * -1);
		memberMileageHistoryService.saveMileageHistoryWhenBuyingProduct(member, mostExpensiveProductName,
			mileage, storeStockCount);
	}

	private void refundMileageWhenCompensating(Receipt receipt,
		List<StoreStockReceiptMapping> storeStockReceiptMappings) {
		if (receipt.getMileage() == null || receipt.getMileage() <= 0) {
			return;
		}

		String mostExpensiveProductName = storeStockReceiptMappings.stream()
			.max(Comparator.comparing(mapping -> mapping.getStoreStock().getPrice()))
			.map(mapping -> mapping.getStoreStock().getWhiskyProduct().getKoreanName())
			.orElse("");

		receipt.getMember().awardMileage(receipt.getMileage());
		memberMileageHistoryService.saveMileageHistoryWhenCanceledReservation(receipt.getMember(),
			mostExpensiveProductName, receipt.getMileage(), storeStockReceiptMappings.size());
	}

	private void deleteShoppingCart(Long memberIdFromJwt, List<ReceiptRequest> receiptRequests) {
		List<Long> shoppingCartIds = receiptRequests.stream()
			.flatMap(receiptRequest -> receiptRequest.getOrderStoreStocks().stream()
				.map(StoreStockReceiptMappingRequest::getShoppingCartId))
			.toList();

		if (!shoppingCartIds.isEmpty()) {
			// 장바구니 삭제
			shoppingCartCommandService.deleteAllByIds(shoppingCartIds);

			// 장바구니 수량 삭제
			redisShoppingCartQuantityUtil.deleteShoppingCartsQuantity(shoppingCartIds, memberIdFromJwt);
		}
	}

	private List<StoreStockReceiptMapping> saveRecipientsAndStoreStockMappings(Receipt receipt,
		List<ReceiptRequest> receiptRequests, boolean isReservation) {
		List<StoreStockReceiptMapping> storeStockReceiptMappings = new ArrayList<>();

		receiptRequests.forEach(receiptRequest -> {
			// Recipient 저장
			Recipient recipient = recipientCommandService.save(receiptRequest.getRecipient().toRecipientEntity());

			// StoreStock 처리
			List<Long> storeStockIds = getStoreStockIds(receiptRequest);
			Map<Long, StoreStock> storeStockMap = storeStockQueryService.findAllByIdsWithStoreAndIsDeletedFalseWithLock(
				storeStockIds);

			receiptValidator.validateStoreStockNotHidden(storeStockMap, storeStockIds);

			Store store = storeStockMap.get(storeStockIds.get(0)).getStore();

			// pickup 날짜 검증
			receiptValidator.validatePickUpDate(store, receiptRequest.getPickUpDate(), storeStockMap);

			// 수량 및 가격 검증
			receiptValidator.validateStockQuantityAndPrice(receiptRequest, storeStockMap, receipt.getMember());

			// StoreStockReceiptMapping 저장
			storeStockReceiptMappings.addAll(
				saveStoreStockReceiptMappings(receiptRequest, recipient, receipt, storeStockMap, isReservation));

			// PickUp 저장
			pickUpCommandService.save(PickUp.of(store, receipt, receiptRequest.getPickUpDate()));

			// StoreStock 수량 업데이트
			updateStoreStockQuantities(receiptRequest, storeStockMap);
		});
		return storeStockReceiptMappings;
	}

	private void startLockHoldMeasurement(AtomicLong lockNanos) {
		if (lockNanos == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}

		long lockStart = System.nanoTime();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				lockNanos.addAndGet(System.nanoTime() - lockStart);
			}
		});
	}

	public long calculateTotalPrice(List<ReceiptRequest> receiptRequests) {
		return receiptRequests.stream()
			.mapToLong(this::calculateReceiptTotal)
			.sum();
	}

	private long calculateReceiptTotal(ReceiptRequest receiptRequest) {
		return receiptRequest.getOrderStoreStocks().stream()
			.mapToLong(s -> s.getPrice() * s.getQuantity())
			.sum();
	}


	// StoreStock Ids 추출
	private List<Long> getStoreStockIds(ReceiptRequest receiptRequest) {
		return receiptRequest.getOrderStoreStocks().stream()
			.map(StoreStockReceiptMappingRequest::getId)
			.toList();
	}

	// StoreStockReceiptMapping 저장 (예약(RESERVATION)이면 paymentPrice = 0)
	private List<StoreStockReceiptMapping> saveStoreStockReceiptMappings(ReceiptRequest receiptRequest,
		Recipient recipient,
		Receipt receipt, Map<Long, StoreStock> storeStockMap, boolean isReservation) {
		List<StoreStockReceiptMapping> storeStockReceiptMappings = receiptRequest.getOrderStoreStocks().stream()
			.map(storeStock -> isReservation
				? storeStock.toReservationStoreStockReceiptMappingEntity(storeStockMap.get(storeStock.getId()),
					receipt, recipient)
				: storeStock.toStoreStockReceiptMappingEntity(storeStockMap.get(storeStock.getId()),
					receipt, recipient))
			.toList();

		return storeStockReceiptCommandService.saveAll(storeStockReceiptMappings);
	}

	// StoreStock 수량 업데이트
	private void updateStoreStockQuantities(ReceiptRequest receiptRequest, Map<Long, StoreStock> storeStockMap) {
		receiptRequest.getOrderStoreStocks().forEach(storeStock -> {
			StoreStock storeStockEntity = storeStockMap.get(storeStock.getId());
			storeStockEntity.decreaseQuantity(storeStock.getQuantity());
		});
	}

	// ... (deep-dive 범위 외 생략: 주문 조회 findAll/findDetail, 취소·수수료 계산 cancel/findCancellationFee,
	//      예약 saveReservation, 픽업 기한 경과 자동취소 pickupDatePassedCancellation 등)
}
