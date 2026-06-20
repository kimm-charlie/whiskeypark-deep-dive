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

	// 주문 준비(TX1): 주문서 저장 + recipient/storeStockReceiptMapping 저장(재고 락·차감). Toss 결제·마일리지 차감은 결제 성공 후(TX2)에 처리한다.
	public Receipt prepareOrder(Member member, ReceiptSaveRequest receiptSaveRequest, long totalPrice,
		AtomicLong lockNanos) {

		// 1. 주문서 저장
		Receipt receipt = receiptCommandService.save(
			receiptSaveRequest.toReceiptEntity(member, totalPrice, receiptSaveRequest.getPaymentTotalPrice()));

		// 2. 락 점유 시간 측정 시작 — 비관락 선점 직전부터 트랜잭션 종료까지
		startLockHoldMeasurement(lockNanos);

		// 3. recipient 및 storeStockReceiptMapping 저장 (이 안에서 StoreStock 비관락 선점 + 재고 차감)
		saveRecipientsAndStoreStockMappings(receipt, receiptSaveRequest.getReceiptRequests(), false);

		return receipt;
	}

	// Toss 결제 승인(Facade) 후 TX2: receipt 에 결제정보 연결 + 마일리지 차감 + 장바구니 삭제.
	// 멀티 트랜잭션 분리로 receipt·member 는 이전 트랜잭션에서 분리(detached)되므로 이 트랜잭션에서 재조회해 영속 상태로 다룬다.
	public void completeOrder(Long receiptId, Long memberId, PaymentInfo paymentInfo,
		ReceiptSaveRequest receiptSaveRequest, AtomicLong lockNanos) {
		Receipt receipt = receiptQueryService.findById(receiptId);
		receipt.updatedPaymentInfo(paymentInfo);

		// 마일리지 차감 — 비관락으로 member 를 선점한 뒤 재검증 후 차감한다.
		// 사전검증(Facade)은 빠른 실패용이고, 동시 요청 race 에서의 정합성은 여기서 보장한다.
		if (receiptSaveRequest.getMileage() > 0) {
			startLockHoldMeasurement(lockNanos);
			Member member = memberQueryService.findByIdWithLock(memberId);
			receiptValidator.validateMileageUsage(receiptSaveRequest.getReceiptRequests(),
				receiptSaveRequest.getMileage(), member, receiptSaveRequest.getPaymentTotalPrice());
			deductMileage(member, receiptSaveRequest.getReceiptRequests(), receiptSaveRequest.getMileage());
		}

		deleteShoppingCart(memberId, receiptSaveRequest.getReceiptRequests());
	}

	/**
	 * 결제 실패 보상(TX3): TX1 에서 만든 주문 흔적을 되돌린다.
	 * 차감했던 재고를 복원하고 receipt + mapping + pickup + recipient 를 하드 삭제한다.
	 * (마일리지는 TX2 에서만 차감되므로 보상 시 환급 대상이 아니다.)
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
		receiptCommandService.delete(receipt);

		log.warn("[RECEIPT_COMPENSATED_DELETE] 결제 실패 보상 — receiptId: {} 재고 복원 및 주문 흔적 삭제 완료", receiptId);
	}

	// 마일리지 차감 + 사용 내역 저장. 검증은 호출 전에 완료되어 있어야 한다(주문 저장 흐름은 결제 전 Facade 사전검증).
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
