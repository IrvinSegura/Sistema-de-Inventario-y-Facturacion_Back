package com.irvin.inventory.sale;

import java.math.BigDecimal;
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
import com.irvin.inventory.common.exception.ForbiddenException;
import com.irvin.inventory.common.exception.NotFoundException;
import com.irvin.inventory.customer.Customer;
import com.irvin.inventory.customer.CustomerRepository;
import com.irvin.inventory.sale.dto.CancelSaleRequest;
import com.irvin.inventory.sale.dto.CreateSaleRequest;
import com.irvin.inventory.sale.dto.SaleResponse;
import com.irvin.inventory.sale.dto.SaleSummaryResponse;
import com.irvin.inventory.security.CurrentUser;
import com.irvin.inventory.stock.InventoryMovementRepository;
import com.irvin.inventory.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Lock order: idempotency key -> seller's cash drawer (FOR SHARE) -> customer (FOR SHARE) -> product rows by id.
// Cancellation: the seller's drawer of today (FOR SHARE) -> sale row -> product rows by id.
@Service
@RequiredArgsConstructor
public class SaleService {

    private static final String IDEMPOTENCY_SCOPE = "sale";
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "saleDate", "id");
    // Orders a client may ask for (API name -> entity path); the default one breaks ties.
    private static final Map<String, String> SORTABLE = Map.of(
            "saleNumber", "saleNumber",
            "saleDate", "saleDate",
            "total", "total",
            "paymentMethod", "paymentMethod",
            "status", "status",
            "customer", "customer.name",
            "seller", "user.name",
            "cancelledAt", "cancelledAt");
    // Largest value of NUMERIC(12,2).
    private static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");
    private static final int MONEY_SCALE = 2;
    // sale_date / cancelled_at are stamped inside the transaction but become visible at commit.
    // Bounding the transaction bounds that gap, which the cash closing's "closed" margin relies on.
    public static final int TRANSACTION_TIMEOUT_SECONDS = 30;

    private final SaleRepository saleRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final IdempotencyLock idempotencyLock;
    private final CashDrawerService cashDrawerService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public record CreateResult(SaleResponse sale, boolean created) {
    }

    // Header, items, stock decrements and inventory movements are saved in one transaction.
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public CreateResult create(CreateSaleRequest request, UUID idempotencyKey, CurrentUser seller) {
        // Managers have no drawer, so their sales would land in none.
        if (seller.isManager()) {
            throw new ForbiddenException("Only sellers record sales: managers have no cash drawer");
        }
        Long userId = seller.id();
        ensureNoDuplicateProducts(request);
        idempotencyLock.acquire(IDEMPOTENCY_SCOPE, idempotencyKey);
        Optional<Sale> existing = saleRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            // Another seller's key counts as a mismatch, so a replay never reveals someone else's sale.
            if (!existing.get().getUser().getId().equals(userId) || !isSameRequest(existing.get(), request)) {
                throw new ConflictException("Idempotency-Key was already used with a different request");
            }
            return new CreateResult(SaleResponse.from(existing.get()), false);
        }

        // PostgreSQL stores microseconds; truncating keeps this response equal to later reads.
        Instant saleDate = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // After the replay check: retrying a recorded sale still works once the drawer is closed.
        CashDrawer drawer = cashDrawerService.requireOpenForSale(userId, saleDate);
        Customer customer = lockActiveCustomer(request.customerId());
        // Product id order: concurrent sales and purchases lock product rows in the same order.
        List<CreateSaleRequest.Item> items = request.items().stream()
                .sorted(Comparator.comparing(CreateSaleRequest.Item::productId))
                .toList();
        Map<Long, Product> products = loadActiveProducts(items);

        Sale sale = new Sale();
        sale.setCustomer(customer);
        sale.setUser(userRepository.getReferenceById(userId));
        sale.setCashDrawer(drawer);
        sale.setSaleDate(saleDate);
        sale.setPaymentMethod(request.paymentMethod());
        sale.setIdempotencyKey(idempotencyKey);
        BigDecimal subtotal = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal tax = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (CreateSaleRequest.Item item : items) {
            SaleItem saleItem = SaleItem.of(products.get(item.productId()), item.quantity());
            sale.addItem(saleItem);
            subtotal = subtotal.add(saleItem.getSubtotal());
            tax = tax.add(saleItem.getTax());
        }
        BigDecimal total = subtotal.add(tax);
        // Every line amount is <= total, so this keeps all of them within NUMERIC(12,2).
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new BadRequestException("Sale total exceeds the maximum allowed");
        }
        sale.setSubtotal(subtotal);
        sale.setTax(tax);
        sale.setTotal(total);
        // Flushed so the inventory movements can reference the sale id; also reads back the sale number.
        saleRepository.saveAndFlush(sale);

        for (CreateSaleRequest.Item item : items) {
            // Empty: another sale took the stock (or the product was deactivated) after it was loaded.
            inventoryMovementRepository.recordSale(item.productId(), item.quantity(), sale.getId(), userId)
                    .orElseThrow(() -> new ConflictException("Insufficient stock for product: " + item.productId()));
        }
        return new CreateResult(SaleResponse.from(sale), true);
    }

    // The seller's drawer comes first and cancelled_at is stamped after it, so a closing in progress
    // either counts this refund or ends before it (the refund then comes out of no drawer). Then the
    // status change: a concurrent cancellation updates no rows and never touches stock.
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public SaleResponse cancel(Long id, CancelSaleRequest request, Long userId) {
        // The seller never changes, so reading it without a lock is safe.
        Long sellerId = saleRepository.findSellerIdById(id).orElseThrow(SaleService::notFound);
        Optional<CashDrawer> refundDrawer = cashDrawerService.lockForCancellation(sellerId, clock.instant());
        Instant cancelledAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (saleRepository.cancel(id, cancelledAt, userId, request.reason()) == 0) {
            throw new ConflictException("Sale is already cancelled");
        }
        refundDrawer.ifPresent(drawer -> saleRepository.chargeCancellation(id, drawer.getId()));
        Sale sale = saleRepository.findDetailedById(id).orElseThrow(SaleService::notFound);
        sale.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .forEach(item -> inventoryMovementRepository.recordSaleCancellation(
                        item.getProduct().getId(), item.getQuantity(), id, userId));
        eventPublisher.publishEvent(new SaleCancelledEvent(id, sale.getSaleNumber(), sellerId, userId,
                sale.getTotal(), request.reason()));
        return SaleResponse.from(sale);
    }

    @Transactional(readOnly = true)
    public Page<SaleSummaryResponse> search(SaleSearch search, Pageable pageable, CurrentUser viewer) {
        // Completed sales have no cancellation date: they would come first in a descending order.
        if (pageable.getSort().getOrderFor("cancelledAt") != null && !search.onlyCancelled()) {
            throw new BadRequestException("Sorting by cancelledAt needs status=CANCELLED or a cancellation date range");
        }
        SaleSearch scoped = viewer.isManager() ? search : search.withSeller(viewer.id());
        Pageable sorted = Sorting.apply(pageable, SORTABLE, DEFAULT_SORT);
        return saleRepository.findAll(scoped.toSpecification(), sorted).map(SaleSummaryResponse::from);
    }

    // A seller asking for another seller's sale gets 404, so ids of other sales aren't confirmed.
    @Transactional(readOnly = true)
    public SaleResponse findById(Long id, CurrentUser viewer) {
        Sale sale = saleRepository.findDetailedById(id)
                .filter(found -> viewer.isManager() || found.getUser().getId().equals(viewer.id()))
                .orElseThrow(SaleService::notFound);
        return SaleResponse.from(sale);
    }

    private Customer lockActiveCustomer(Long customerId) {
        // A missing walk-in customer is a seeding problem (500), not a bad request.
        Customer found = customerId == null
                ? customerRepository.findByTaxIdForShare(Customer.WALK_IN_TAX_ID)
                        .orElseThrow(() -> new IllegalStateException("Walk-in customer is missing"))
                : customerRepository.findByIdForShare(customerId)
                        .orElseThrow(() -> new BadRequestException("Customer not found"));
        if (!found.isActive()) {
            throw new BadRequestException("Customer is inactive");
        }
        return found;
    }

    private static void ensureNoDuplicateProducts(CreateSaleRequest request) {
        long distinct = request.items().stream().map(CreateSaleRequest.Item::productId).distinct().count();
        if (distinct != request.items().size()) {
            throw new BadRequestException("Each product can appear only once per sale");
        }
    }

    private Map<Long, Product> loadActiveProducts(List<CreateSaleRequest.Item> items) {
        List<Long> ids = items.stream().map(CreateSaleRequest.Item::productId).toList();
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
    private static boolean isSameRequest(Sale sale, CreateSaleRequest request) {
        boolean sameCustomer = request.customerId() == null
                ? sale.getCustomer().isWalkIn()
                : sale.getCustomer().getId().equals(request.customerId());
        if (!sameCustomer
                || sale.getPaymentMethod() != request.paymentMethod()
                || sale.getItems().size() != request.items().size()) {
            return false;
        }
        Map<Long, Integer> stored = sale.getItems().stream()
                .collect(Collectors.toMap(item -> item.getProduct().getId(), SaleItem::getQuantity));
        return request.items().stream().allMatch(item -> item.quantity().equals(stored.get(item.productId())));
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Sale not found");
    }
}
