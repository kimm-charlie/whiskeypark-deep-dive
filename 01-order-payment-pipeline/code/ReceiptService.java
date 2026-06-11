package be.weskey.module.member.receipt.service;

import static be.weskey.shared.constant.MessageConstant.*;
import static be.weskey.shared.constant.PageableConstant.*;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import be.weskey.common.utils.DateUtil;
import be.weskey.exception.CustomRuntimeException;
import be.weskey.exception.exceptions.ReceiptException;
import be.weskey.module.member.kakao_alrim.service.KakaoAlrimServiceForWhiskyPark;
import be.weskey.module.member.member.entity.Member;
import be.weskey.module.member.member.service.MemberQueryService;
import be.weskey.module.member.member_mileage_history.service.MemberMileageHistoryService;
import be.weskey.module.member.pick_up.entity.PickUp;
import be.weskey.module.member.pick_up.service.PickUpCommandService;
import be.weskey.module.member.pick_up.service.PickUpQueryService;
import be.weskey.module.member.receipt.dto.ReceiptRefundCommand;
import be.weskey.module.member.receipt.dto.request.ReceiptCancelRequest;
import be.weskey.module.member.receipt.dto.request.ReceiptRequest;
import be.weskey.module.member.receipt.dto.request.ReceiptReservationSaveRequest;
import be.weskey.module.member.receipt.dto.request.ReceiptSaveRequest;
import be.weskey.module.member.receipt.dto.response.ReceiptFindAllResponse;
import be.weskey.module.member.receipt.dto.response.ReceiptFindCancellationFeeResponse;
import be.weskey.module.member.receipt.dto.response.ReceiptFindDetailResponse;
import be.weskey.module.member.receipt.dto.response.ReceiptFindPickUpReservationResponse;
import be.weskey.module.member.receipt.entity.Receipt;
import be.weskey.module.member.receipt.validator.ReceiptValidator;
import be.weskey.module.member.recipient.entity.Recipient;
import be.weskey.module.member.recipient.service.RecipientCommandService;
import be.weskey.module.member.shopping_cart.service.ShoppingCartCommandService;
import be.weskey.module.member.store.entity.Store;
import be.weskey.module.member.store_business_hour.entity.StoreBusinessHour;
import be.weskey.module.member.store_holiday.entity.StoreHoliday;
import be.weskey.module.member.store_stock.entity.StoreStock;
import be.weskey.module.member.store_stock.service.StoreStockQueryService;
import be.weskey.module.member.store_stock_receipt_mapping.dto.request.StoreStockReceiptMappingRequest;
import be.weskey.module.member.store_stock_receipt_mapping.entity.StoreStockReceiptMapping;
import be.weskey.module.member.store_stock_receipt_mapping.service.StoreStockReceiptCommandService;
import be.weskey.module.member.store_stock_receipt_mapping.service.StoreStockReceiptMappingQueryService;
import be.weskey.module.member.whisky_product.entity.WhiskyProduct;
import be.weskey.module.member.whisky_product.service.WhiskyProductService;
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
	private final KakaoAlrimServiceForWhiskyPark kakaoAlrimService;
	private final MemberMileageHistoryService memberMileageHistoryService;
	private final MemberQueryService memberQueryService;
	private final WhiskyProductService whiskyProductService;
	private final Clock clock;
	private final ReceiptValidator receiptValidator;
	private final PaymentInfoQueryService paymentInfoQueryService;
	private final MeterRegistry meterRegistry;

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
	public Receipt prepareOrder(Member member, ReceiptSaveRequest receiptSaveRequest, long totalPrice) {

		// 1. 주문서 저장
		Receipt receipt = receiptCommandService.save(
			receiptSaveRequest.toReceiptEntity(member, totalPrice, receiptSaveRequest.getPaymentTotalPrice()));

		// 2. (마일리지 검증은 결제 전 Facade 사전검증, 차감은 결제 성공 후 TX2 에서 수행한다)

		// 3. 재고 락 점유 시간 측정 시작 — StoreStock 비관락 선점 직전부터 트랜잭션 커밋(락 해제) 시점까지를
		//    receipt.save.lock 으로 기록한다. (G6 분리 전: Toss 호출까지 포함된 락 점유 / 분리 후: DB 작업만)
		//    계측이 주문 흐름을 깨지 않도록 트랜잭션 동기화가 활성일 때만 등록한다.
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			Timer.Sample lockHoldSample = Timer.start(meterRegistry);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					lockHoldSample.stop(meterRegistry.timer("receipt.save.lock"));
				}
			});
		}

		// 4. recipient 및 storeStockReceiptMapping 저장 (이 안에서 StoreStock 비관락 선점 + 재고 차감)
		saveRecipientsAndStoreStockMappings(receipt, receiptSaveRequest.getReceiptRequests(), false);

		return receipt;
	}

	// Toss 결제 승인(Facade) 후 TX2: receipt 에 결제정보 연결 + 마일리지 차감 + 장바구니 삭제.
	// 멀티 트랜잭션 분리로 receipt·member 는 이전 트랜잭션에서 분리(detached)되므로 이 트랜잭션에서 재조회해 영속 상태로 다룬다.
	public void completeOrder(Long receiptId, Long memberId, PaymentInfo paymentInfo,
		ReceiptSaveRequest receiptSaveRequest) {
		Receipt receipt = receiptQueryService.findById(receiptId);
		receipt.updatedPaymentInfo(paymentInfo);

		// 마일리지 차감(검증은 결제 전 Facade 사전검증에서 완료). 결제 성공 후에만 차감하므로 실패 시 환급이 필요 없다.
		// awardMileage 의 dirty checking 을 위해 영속 상태의 member 가 필요할 때만 재조회한다.
		if (receiptSaveRequest.getMileage() > 0) {
			Member member = memberQueryService.findById(memberId);
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

		// 2. 주문 흔적 하드 삭제 — FK 의존상 mapping → pickup → recipient → receipt 순서로 삭제한다.
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

	// 예약(RESERVATION) receipt 생성: Toss 결제를 호출하지 않고, paymentInfo 없이 저장
	public Long saveReservation(Member member, ReceiptReservationSaveRequest request, long totalPrice) {

		// 1. 주문서 저장 (paymentInfo = null, paymentType = RESERVATION, paymentTotalPrice = 0)
		Receipt receipt = receiptCommandService.save(request.toReceiptEntity(member, totalPrice));

		// 2. mileage 사용에 대한 검증 및 마일리지 소모 내역 저장
		if (request.getMileage() > 0) {
			processMileageUsage(member, request.getReceiptRequests(), request.getMileage(), totalPrice);
		}

		// 3. recipient 및 storeStockReceiptMapping 저장 (paymentPrice = 0 강제)
		saveRecipientsAndStoreStockMappings(receipt, request.getReceiptRequests(), true);

		// 4. (Toss 결제 호출 스킵)
		// 5. (paymentInfo 업데이트 스킵)

		// 6. shoppingCart 삭제
		deleteShoppingCart(member.getId(), request.getReceiptRequests());

		return receipt.getId();
	}

	// 예약(saveReservation) 흐름용: 마일리지 검증 + 차감을 단일 트랜잭션에서 함께 수행한다.
	private void processMileageUsage(Member member, List<ReceiptRequest> receiptRequests, Integer mileage,
		long totalPrice) {
		receiptValidator.validateMileageUsage(receiptRequests, mileage, member, totalPrice);
		deductMileage(member, receiptRequests, mileage);
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

	public long calculateTotalPrice(ReceiptSaveRequest receiptSaveRequest) {
		return calculateTotalPrice(receiptSaveRequest.getReceiptRequests());
	}

	public long calculateTotalPrice(List<ReceiptRequest> receiptRequests) {
		return receiptRequests.stream()
			.mapToLong(this::calculateReceiptTotal)
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

	private long calculateReceiptTotal(ReceiptRequest receiptRequest) {
		return receiptRequest.getOrderStoreStocks().stream()
			.mapToLong(this::calculateStockTotal)
			.sum();
	}

	private long calculateStockTotal(StoreStockReceiptMappingRequest storeStockRequest) {
		return storeStockRequest.getPrice() * storeStockRequest.getQuantity();
	}

	public ReceiptFindAllResponse findAll(Long memberId, int page) {
		Pageable receiptFindAllPageable = PageRequest.of(page, PAGE_SIZE_FOR_RECEIPT_FIND_ALL_SIZE);

		Slice<Receipt> receipts = receiptQueryService.findAllByMemberId(memberId, receiptFindAllPageable);

		List<Long> receiptIds = receipts.stream().map(Receipt::getId).toList();

		Map<Long, List<PickUp>> pickUps = pickUpQueryService.findAllByReceiptIds(receiptIds);
		Map<Long, List<StoreStockReceiptMapping>> storeStockReceiptMappings = storeStockReceiptMappingQueryService.findAllByReceiptIds(
			receiptIds);

		return ReceiptFindAllResponse.from(receipts, pickUps, storeStockReceiptMappings);
	}

	public ReceiptFindDetailResponse findDetail(Receipt receipt) {
		List<PickUp> pickUps = pickUpQueryService.findAllByReceiptId(receipt.getId());

		List<WhiskyProduct> whiskyProducts = receipt.getStoreStockReceiptMappings().stream()
			.map(mapping -> mapping.getStoreStock().getWhiskyProduct())
			.distinct()
			.toList();
		Map<Long, List<WhiskyProduct>> writableProductsByWhiskyProductId = whiskyProductService
			.findWritableTastingNoteProductsByProducts(whiskyProducts);

		return ReceiptFindDetailResponse.from(receipt, pickUps, writableProductsByWhiskyProductId);
	}

	// 취소 처리(취소가능 검증/수수료 계산/재고 복원/마일리지 환급) 후 환불 금액을 반환한다. Toss 부분취소 호출은 Facade가 담당.
	public long cancel(Receipt receipt, ReceiptCancelRequest request) {
		// 1. 취소 가능 상품 조회 및 검증
		List<StoreStockReceiptMapping> storeStockReceiptMappings = storeStockReceiptMappingQueryService.findAllByReceiptIdWithLock(
			receipt.getId());

		receiptValidator.validateCancelableProducts(storeStockReceiptMappings);

		List<PickUp> pickUps = pickUpQueryService.findAllByReceiptId(receipt.getId());

		// 현재 취소할 상품들의 합산 결제금액을 구해놓는다.
		Long totalPaymentPrice = storeStockReceiptMappings.stream()
			.mapToLong(StoreStockReceiptMapping::getPaymentPrice)
			.sum();

		//2. 전체 취소 수수료 계산 및 storeId 별 수수료 맵핑
		Map<Long, Long> cancellationFeeByStoreMap = calculateCancellationFees(storeStockReceiptMappings, pickUps,
			receipt);

		//3. receipt 갱신
		updateReceiptCancellationInfo(receipt, request.getCancellationFee(), cancellationFeeByStoreMap,
			storeStockReceiptMappings);

		//4. 기존 storeStock 의 재고 재증가.
		List<StoreStock> storeStocks = storeStockQueryService.findAllByIdWithLock(storeStockReceiptMappings.stream()
			.map(storeStockReceiptMapping -> storeStockReceiptMapping.getStoreStock().getId())
			.toList());

		// 수량 증가
		storeStocks.forEach(storeStock -> {
			storeStockReceiptMappings.stream()
				.filter(storeStockReceiptMapping -> storeStockReceiptMapping.getStoreStock()
					.getId()
					.equals(storeStock.getId()))
				.findFirst()
				.ifPresent(mapping -> storeStock.increaseQuantity(mapping.getQuantity()));
		});

		//5. 환불 금액 산출 (수수료 제외). Toss 부분취소 호출은 Facade가 담당한다.
		//첫번째 파라미터에 들어갈 값은 취소된 금액중 수수료를 뺀 금액이 들어가야 간다. (환불될 금액이므로 수수료를 뺀 값만 환불을 진행해야한다.)
		Long cancellationAmount = totalPaymentPrice - request.getCancellationFee();

		String mostExpensiveProductName = storeStocks.stream()
			.max(Comparator.comparing(StoreStock::getPrice))
			.map(mapping -> mapping.getWhiskyProduct().getKoreanName())
			.orElse("");

		int storeStockCount = storeStocks.size();

		//6. 마일리지 환급
		if (receipt.getMileage() > 0) {
			memberMileageHistoryService.saveMileageHistoryWhenCanceledReservation(receipt.getMember(),
				mostExpensiveProductName, receipt.getMileage(), storeStockCount);
			receipt.getMember().awardMileage(receipt.getMileage());
		}

		// 예약(RESERVATION) receipt(paymentInfo=null) 또는 환불액 0 이면 0 반환 → Facade가 Toss 호출 스킵
		return (receipt.getPaymentInfo() != null && cancellationAmount > 0) ? cancellationAmount : 0L;
	}

	private void updateReceiptCancellationInfo(Receipt receipt, Long totalCancellationFeeFromRequest,
		Map<Long, Long> cancellationFeeByStoreMap, List<StoreStockReceiptMapping> storeStockReceiptMappings) {
		//4.3 취소된 상품들의 합계 수수료 -> totalCancellationFee
		Long totalCancellationFee = cancellationFeeByStoreMap.values().stream()
			.mapToLong(Long::longValue)
			.sum();

		receiptValidator.validateCancellationFee(totalCancellationFeeFromRequest, totalCancellationFee);

		receipt.updateCancellationInfo(totalCancellationFee);
	}

	private Map<Long, Long> calculateCancellationFees(List<StoreStockReceiptMapping> mappings, List<PickUp> pickUps,
		Receipt receipt) {
		// 예약(RESERVATION) receipt 는 paymentInfo 가 null → 모든 mapping 수수료 0 강제, storeMap 비움
		if (receipt.getPaymentInfo() == null) {
			mappings.forEach(mapping -> mapping.updateCancellationInfo(0L));
			return Map.of();
		}

		Map<Long, Long> cancellationFeeByStoreMap = new HashMap<>();

		for (StoreStockReceiptMapping mapping : mappings) {
			Long storeId = mapping.getStoreStock().getStore().getId();
			long paymentPrice = mapping.getPaymentPrice();
			long cancellationFee = 0L;

			if (mapping.getIsScheduled()) {
				PickUp pickUp = pickUps.stream()
					.filter(p -> p.getStore().getId().equals(storeId))
					.findFirst()
					.orElseThrow(() -> new CustomRuntimeException(ReceiptException.PICK_UP_DATE_ERROR));

				long daysBetween = ChronoUnit.DAYS.between(LocalDate.now(), pickUp.getPickUpDate());
				cancellationFee = calculateCancellationFee(daysBetween, paymentPrice);
			}

			mapping.updateCancellationInfo(cancellationFee);

			cancellationFeeByStoreMap.merge(storeId, cancellationFee, Long::sum); // 같은 스토어의 수수료 합산
		}

		return cancellationFeeByStoreMap;
	}

	private long calculateCancellationFee(long daysBetween, long paymentPrice) {
		if (daysBetween >= 3) {
			return 0L; // 3일 전: 100% 환불
		} else if (daysBetween == 2) {
			return paymentPrice / 2; // 2일 전: 50% 환불
		} else {
			return paymentPrice; // 1일 전 또는 당일: 환불 불가
		}
	}


	public ReceiptFindCancellationFeeResponse findCancellationFee(Receipt receipt) {
		// 예약(RESERVATION) receipt 는 결제가 없어 취소 수수료도 항상 0
		if (receipt.getPaymentInfo() == null) {
			return ReceiptFindCancellationFeeResponse.from(0L);
		}

		// 1. 취소 가능 상품 조회
		List<StoreStockReceiptMapping> storeStockReceiptMappings = receipt.getStoreStockReceiptMappings()
			.stream()
			.filter(mapping -> !mapping.getIsCanceledBySeller() && !mapping.getIsCanceledByBuyer()
				&& !mapping.getIsRefunded())
			.toList();
		List<PickUp> pickUps = pickUpQueryService.findAllByReceiptId(receipt.getId());

		//2. 전체 취소 수수료 계산 및 storeId 별 수수료 맵핑
		Map<Long, Long> cancellationFeeByStoreMap = calculateCancellationFees(storeStockReceiptMappings, pickUps,
			receipt);
		Long totalCancellationFee = cancellationFeeByStoreMap.values().stream()
			.mapToLong(Long::longValue)
			.sum();

		return ReceiptFindCancellationFeeResponse.from(totalCancellationFee);
	}

	/**
	 * 픽업시간이 지났지만 아직 처리되지 않은 주문들에 대해 취소처리를 하는 메서드 이다.
	 */
	public List<ReceiptRefundCommand> pickupDatePassedCancellation() {
		List<Receipt> receipts = receiptQueryService.findAllWithPickUpDatePassed();

		//receipts 에서 isScheduled 가 false 인것이 있으면 어떻게 처리할건지 (위스키파크 책임이 있으니) 주문자체가 취소는 되는데, 예약금은 환불이 되도록

		// Toss 부분취소(예약금 환불) 대상을 모아 반환한다. 실제 Toss 호출은 Facade가 건별로 수행.
		List<ReceiptRefundCommand> refunds = new ArrayList<>();
		receipts.forEach(receipt -> {
			Set<StoreStockReceiptMapping> storeStockReceiptMappings = receipt.getStoreStockReceiptMappings();
			Set<PickUp> pickUps = receipt.getPickUps();

			// 이미 부분 취소/회원 취소/환불 처리된 mapping 은 자동 취소 대상에서 제외
			// (이미 그 시점에 재고 복원/Toss 환불/마일리지 처리가 끝나 있음)
			List<StoreStockReceiptMapping> aliveMappings = storeStockReceiptMappings.stream()
				.filter(StoreStockReceiptMapping::isAlive)
				.toList();

			//1. isScheduled 가 true 인데 pickUp 이 false 인 것들은 고객에 의한 취소로 처리를 한다.
			List<StoreStockReceiptMapping> storeStockReceiptMappingsToCancelByBuyer = aliveMappings.stream()
				.filter(StoreStockReceiptMapping::getIsScheduled)
				.toList();

			// 예약(RESERVATION) receipt 는 paymentInfo 가 null → 수수료 0 강제
			boolean hasPaymentInfo = receipt.getPaymentInfo() != null;
			storeStockReceiptMappingsToCancelByBuyer.forEach(
				storeStockReceiptMapping -> storeStockReceiptMapping.updateCancellationInfo(
					hasPaymentInfo ? storeStockReceiptMapping.getPaymentPrice() : 0L));

			//2. isScheduled 가 false 인것들은 위스키 파크측에서 요청확정을 누르지 않은 상품이기 때문에 위스키 파크측 취소로 처리를 한다. 따라서 예약금액을 환불 한다.
			List<StoreStockReceiptMapping> storeStockReceiptMappingsToCancelBySeller = aliveMappings.stream()
				.filter(storeStockReceiptMapping -> !storeStockReceiptMapping.getIsScheduled())
				.toList();

			storeStockReceiptMappingsToCancelBySeller.forEach(
				storeStockReceiptMapping -> storeStockReceiptMapping.updateCancellationInfoBySeller(
					RECEIPT_CANCELLATION_SCHEDULER_CANCELED_BY_SELLER_REASON));

			//3. receipt 갱신
			Long storeStockReceiptMappingsToCancelByBuyerTotalCancellationFee = storeStockReceiptMappingsToCancelByBuyer.stream()
				.mapToLong(StoreStockReceiptMapping::getCancellationFee)
				.sum();

			receipt.updateCancellationInfo(storeStockReceiptMappingsToCancelByBuyerTotalCancellationFee);

			//4. 기존 storeStock 의 재고 재증가. 부분 취소된 mapping 은 이미 재고 복원되었으므로 제외.
			aliveMappings.forEach(storeStockReceiptMapping -> {
				storeStockReceiptMapping.getStoreStock().increaseQuantity(storeStockReceiptMapping.getQuantity());
			});

			//5. 마일리지를 사용해서 결제했다면, 마일리지 환급 (이미 환급된 receipt 는 mileage=0 이라 자연 차단)
			if (receipt.getMileage() > 0) {
				Member member = receipt.getMember();
				member.awardMileage(receipt.getMileage());
				String mostExpensiveProductName = aliveMappings.stream()
					.max(Comparator.comparing(StoreStockReceiptMapping::getPrice))
					.map(mapping -> mapping.getStoreStock().getWhiskyProduct().getKoreanName())
					.orElse("");

				int storeStockCount = aliveMappings.size();
				memberMileageHistoryService.saveMileageHistoryWhenCanceledReservation(member, mostExpensiveProductName,
					receipt.getMileage(), storeStockCount);
				receipt.refundMileage();
			}

			//6. 예약금 환불 대상 수집 (Toss 부분취소 호출은 Facade가 건별로 수행)
			// 취소금액 0 또는 예약(RESERVATION, paymentInfo=null) receipt 는 제외
			long totalCancellationPrice = storeStockReceiptMappingsToCancelBySeller.stream()
				.mapToLong(StoreStockReceiptMapping::getPaymentPrice).sum();
			if (receipt.getPaymentInfo() != null && totalCancellationPrice > 0) {
				refunds.add(ReceiptRefundCommand.of(receipt, totalCancellationPrice));
			}
		});

		return refunds;
	}

	/**
	 * 이건 3일이내 존재하는 가장 가까운 픽업 예약을 찾아주는 메서드이다.
	 *
	 * @param memberIdFromJwt
	 * @return
	 */
	public ReceiptFindPickUpReservationResponse findNearestReceiptWithin3Days(Long memberIdFromJwt) {
		// 1. pickUpDate 기반으로 3일 이내 가장 가까운 receipt 목록을 가져온다.
		List<Receipt> receiptFromDb = receiptQueryService.findAllNearestReceiptWithin3Days(memberIdFromJwt);

		// 2. storeStockReceiptMapping을 가지고 있는 가장 가까운 Receipt를 찾는다.
		Receipt receipt = receiptFromDb.stream()
			.filter(r -> !r.getStoreStockReceiptMappings().isEmpty())
			.findFirst()
			.orElse(null);

		if (receiptFromDb.isEmpty() || receipt == null) {
			return null;
		} else {
			// 3. Receipt 안의 PickUp 중에서 storeStockReceiptMappings의 storeId와 일치하는 가장 빠른 PickUp을 찾는다.
			Optional<PickUp> optionalPickUp = receipt.getPickUps().stream()
				.filter(pickUp -> receipt.getStoreStockReceiptMappings().stream()
					.anyMatch(mapping -> mapping.getStoreStock().getStore().getId().equals(pickUp.getStore().getId()))
				)
				.min(Comparator.comparing(PickUp::getPickUpDate));

			PickUp pickUp = optionalPickUp.orElseThrow(
				() -> new CustomRuntimeException(ReceiptException.PICK_UP_RESERVATION_NOT_FOUND));

			// 4. 찾은 PickUp의 storeId와 일치하는 storeStockReceiptMappings를 필터링한다
			List<StoreStockReceiptMapping> storeStockReceiptMappings = receipt.getStoreStockReceiptMappings().stream()
				.sorted(Comparator.comparing(StoreStockReceiptMapping::getId))
				.filter(mapping -> mapping.getStoreStock().getStore().getId().equals(pickUp.getStore().getId()))
				.toList();

			return ReceiptFindPickUpReservationResponse.from(receipt, pickUp, storeStockReceiptMappings);
		}
	}
}
