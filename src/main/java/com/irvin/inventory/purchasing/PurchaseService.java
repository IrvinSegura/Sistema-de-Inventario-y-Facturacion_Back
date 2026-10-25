package com.irvin.inventory.purchasing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.irvin.inventory.catalog.Product;
import com.irvin.inventory.catalog.ProductRepository;
import com.irvin.inventory.common.IdempotencyLock;
import com.irvin.inventory.common.Sorting;
import com.irvin.inventory.common.exception.BadRequestException;
import com.irvin.inventory.common.exception.ConflictException;
import com.irvin.inventory.common.exception.NotFoundException;
import com.irvin.inventory.purchasing.dto.CancelPurchaseRequest;
import com.irvin.inventory.purchasing.dto.CreatePurchaseRequest;
import com.irvin.inventory.purchasing.dto.PurchaseResponse;
import com.irvin.inventory.purchasing.dto.PurchaseSummaryResponse;
import com.irvin.inventory.stock.InventoryMovementRepository;
import com.irvin.inventory.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Lock order: idempotency key -> supplier (FOR SHARE) -> product rows by id; a cancellation locks its
// purchase row, then product rows by id.
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private static final String IDEMPOTENCY_SCOPE = "purchase";
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "purchaseDate", "id");
    // Orders a client may ask for (API name -> entity path); the default one breaks ties.
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "purchaseDate", "purchaseDate",
            "total", "total",
            "status", "status",
            "supplier", "supplier.name",
            "recordedBy", "user.name");
    // Largest value of NUMERIC(12,2).
    private static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");
    private static final int MONEY_SCALE = 2;

    private final PurchaseRepository purchaseRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final IdempotencyLock idempotencyLock;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public record CreateResult(PurchaseResponse purchase, boolean created) {
    }

    // Header, items, stock increases and inventory movements are saved in one transaction.
    @Transactional
    public CreateResult create(CreatePurchaseRequest request, UUID idempotencyKey, Long userId) {
        ensureNoDuplicateProducts(request);
        if (idempotencyKey != null) {
            idempotencyLock.acquire(IDEMPOTENCY_SCOPE, idempotencyKey);
            Optional<Purchase> existing = purchaseRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!isSameRequest(existing.get(), request)) {
                    throw new ConflictException("Idempotency-Key was already used with a different request");
                }
                return new CreateResult(PurchaseResponse.from(existing.get()), false);
            }
        }

        Supplier supplier = supplierRepository.findByIdForShare(request.supplierId())
                .orElseThrow(() -> new BadRequestException("Supplier not found"));
        if (!supplier.isActive()) {
            throw new BadRequestException("Supplier is inactive");
        }
        // Product id order: concurrent purchases and sales lock product rows in the same order.
        List<CreatePurchaseRequest.Item> items = request.items().stream()
                .sorted(Comparator.comparing(CreatePurchaseRequest.Item::productId))
                .toList();
        Map<Long, Product> products = loadActiveProducts(items);

        Purchase purchase = new Purchase();
        purchase.setSupplier(supplier);
        purchase.setUser(userRepository.getReferenceById(userId));
        // PostgreSQL stores microseconds; truncating keeps this response equal to later reads.
        purchase.setPurchaseDate(clock.instant().truncatedTo(ChronoUnit.MICROS));
        purchase.setIdempotencyKey(idempotencyKey);
        BigDecimal total = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (CreatePurchaseRequest.Item item : items) {
            // Already validated to 2 decimals, so UNNECESSARY never rounds.
            BigDecimal unitCost = item.unitCost().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
            PurchaseItem purchaseItem = new PurchaseItem();
            purchaseItem.setProduct(products.get(item.productId()));
            purchaseItem.setQuantity(item.quantity());
            purchaseItem.setUnitCost(unitCost);
            purchaseItem.setSubtotal(unitCost.multiply(BigDecimal.valueOf(item.quantity())));
            purchase.addItem(purchaseItem);
            total = total.add(purchaseItem.getSubtotal());
        }
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new BadRequestException("Purchase total exceeds the maximum allowed");
        }
        purchase.setTotal(total);
        // Flushed so the inventory movements can reference the purchase id.
        purchaseRepository.saveAndFlush(purchase);

        for (CreatePurchaseRequest.Item item : items) {
            // Empty means the product was deactivated after it was loaded above.
            BigDecimal unitCost = item.unitCost().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
            inventoryMovementRepository
                    .recordPurchase(item.productId(), item.quantity(), unitCost, purchase.getId(), userId)
                    .orElseThrow(() -> new BadRequestException("Product is inactive: " + item.productId()));
        }
        return new CreateResult(PurchaseResponse.from(purchase), true);
    }

    // Status change first: a concurrent cancellation updates no rows and never touches stock. Then the
    // units leave by product id, in the same order as purchases and sales; if any product has fewer units
    // left than the purchase brought (already sold), the whole cancellation rolls back.
    @Transactional
    public PurchaseResponse cancel(Long id, CancelPurchaseRequest request, Long userId) {
        Instant cancelledAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (purchaseRepository.cancel(id, cancelledAt, userId, request.reason()) == 0) {
            throw purchaseRepository.existsById(id)
                    ? new ConflictException("Purchase is already cancelled")
                    : notFound();
        }
        Purchase purchase = purchaseRepository.findDetailedById(id).orElseThrow(PurchaseService::notFound);
        purchase.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .forEach(item -> inventoryMovementRepository
                        .recordPurchaseCancellation(item.getProduct().getId(), item.getQuantity(),
                                item.getUnitCost(), id, userId)
                        .orElseThrow(() -> new ConflictException(
                                "Insufficient stock for product: " + item.getProduct().getId())));
        eventPublisher.publishEvent(new PurchaseCancelledEvent(id, purchase.getSupplier().getName(), userId,
                purchase.getTotal(), request.reason()));
        return PurchaseResponse.from(purchase);
    }

    @Transactional(readOnly = true)
    public Page<PurchaseSummaryResponse> search(PurchaseSearch search, Pageable pageable) {
        Pageable sorted = Sorting.apply(pageable, SORTABLE, DEFAULT_SORT);
        return purchaseRepository.findAll(search.toSpecification(), sorted).map(PurchaseSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public PurchaseResponse findById(Long id) {
        return PurchaseResponse.from(purchaseRepository.findDetailedById(id).orElseThrow(PurchaseService::notFound));
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Purchase not found");
    }

    private static void ensureNoDuplicateProducts(CreatePurchaseRequest request) {
        long distinct = request.items().stream().map(CreatePurchaseRequest.Item::productId).distinct().count();
        if (distinct != request.items().size()) {
            throw new BadRequestException("Each product can appear only once per purchase");
        }
    }

    private Map<Long, Product> loadActiveProducts(List<CreatePurchaseRequest.Item> items) {
        List<Long> ids = items.stream().map(CreatePurchaseRequest.Item::productId).toList();
        Map<Long, Product> products = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Long id : ids) {
            Product product = products.get(id);
            if (product == null) {
                throw new BadRequestException("Product not found: " + id);
            }
            if (!product.isActive()) {
                throw new BadRequestException("Product is inactive: " + id);
            }
        }
        return products;
    }

    // A retried request must be identical; reusing a key for different data is a client bug.
    // Called after the duplicate-product check, so comparing by product id is unambiguous.
    private static boolean isSameRequest(Purchase purchase, CreatePurchaseRequest request) {
        if (!purchase.getSupplier().getId().equals(request.supplierId())
                || purchase.getItems().size() != request.items().size()) {
            return false;
        }
        Map<Long, PurchaseItem> stored = purchase.getItems().stream()
                .collect(Collectors.toMap(item -> item.getProduct().getId(), Function.identity()));
        return request.items().stream().allMatch(item -> {
            PurchaseItem match = stored.get(item.productId());
            return match != null
                    && match.getQuantity() == item.quantity()
                    && match.getUnitCost().compareTo(item.unitCost()) == 0;
        });
    }
}
