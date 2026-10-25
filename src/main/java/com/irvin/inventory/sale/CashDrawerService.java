package com.irvin.inventory.sale;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.irvin.inventory.common.Sorting;
import com.irvin.inventory.common.exception.BadRequestException;
import com.irvin.inventory.common.exception.ConflictException;
import com.irvin.inventory.common.exception.ForbiddenException;
import com.irvin.inventory.common.exception.NotFoundException;
import com.irvin.inventory.config.StoreProperties;
import com.irvin.inventory.sale.dto.AssignableSellerResponse;
import com.irvin.inventory.sale.dto.CashDrawerResponse;
import com.irvin.inventory.sale.dto.CloseCashDrawerRequest;
import com.irvin.inventory.sale.dto.OpenAssignedCashDrawerRequest;
import com.irvin.inventory.sale.dto.OpenCashDrawerRequest;
import com.irvin.inventory.sale.dto.SaleResponse.Ref;
import com.irvin.inventory.security.CurrentUser;
import com.irvin.inventory.user.Role;
import com.irvin.inventory.user.User;
import com.irvin.inventory.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// A drawer per local day with one or more sellers as members: each seller is in one drawer per day and
// one open drawer at a time. Managers have none (they see every expected cash, so their count can't be
// blind). Sales and cancellations lock the drawer FOR SHARE and closing locks it FOR UPDATE, so the
// expected cash includes every sale and cancellation recorded in it before the closing.
@Service
@RequiredArgsConstructor
public class CashDrawerService {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "businessDate", "id");
    // Orders a client may ask for (API name -> entity path); the default one breaks ties.
    private static final Map<String, String> SORTABLE = Map.of(
            "businessDate", "businessDate",
            "openingFloat", "openingFloat",
            "expectedCash", "expectedCash",
            "countedCash", "countedCash",
            "openedAt", "openedAt",
            "closedAt", "closedAt");
    private static final int MONEY_SCALE = 2;
    private static final String MANAGERS_HAVE_NO_DRAWER =
            "Only sellers have a cash drawer; managers open them for sellers";

    private final CashDrawerRepository cashDrawerRepository;
    private final CashDrawerMemberRepository memberRepository;
    private final SaleRepository saleRepository;
    private final UserRepository userRepository;
    private final StoreProperties storeProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // The seller's personal drawer for today in the store's time zone: they are its only member.
    @Transactional
    public CashDrawerResponse open(OpenCashDrawerRequest request, CurrentUser actor) {
        if (actor.isManager()) {
            throw new ForbiddenException(MANAGERS_HAVE_NO_DRAWER);
        }
        Long userId = actor.id();
        Instant now = now();
        LocalDate today = today(now);
        // At most one row: today's drawer can't have opened while an older one was still open.
        memberRepository.findOpenOrOnDay(List.of(userId), today).stream().findFirst().ifPresent(taken -> {
            throw new ConflictException(!taken.isDrawerOpen()
                    ? "Today's cash drawer is already closed"
                    : taken.getBusinessDate().equals(today)
                            ? "Your cash drawer is already open"
                            : "Close your cash drawer of " + taken.getBusinessDate() + " first");
        });
        User user = userRepository.getReferenceById(userId);
        return save(CashDrawer.open(user, user, List.of(user), today, request.openingFloat().setScale(MONEY_SCALE), now));
    }

    // A manager opens today's drawer for one or several sellers and hands over the float.
    @Transactional
    public CashDrawerResponse openAssigned(OpenAssignedCashDrawerRequest request, Long managerId) {
        List<Long> memberIds = request.memberIds();
        if (new HashSet<>(memberIds).size() != memberIds.size()) {
            throw new BadRequestException("Each seller can be assigned only once");
        }
        if (!memberIds.contains(request.responsibleId())) {
            throw new BadRequestException("The responsible must be one of the assigned sellers");
        }
        Map<Long, User> users = userRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        for (Long id : memberIds) {
            User user = users.get(id);
            if (user == null) {
                throw new BadRequestException("Seller not found: " + id);
            }
            if (!user.isActive()) {
                throw new BadRequestException("Seller is inactive: " + user.getName());
            }
            if (user.getRole() != Role.SELLER) {
                throw new BadRequestException("Only sellers can be assigned a cash drawer: " + user.getName());
            }
        }
        Instant now = now();
        LocalDate today = today(now);
        memberRepository.findOpenOrOnDay(memberIds, today).stream().findFirst().ifPresent(taken -> {
            String name = taken.getUser().getName();
            throw new ConflictException(!taken.isDrawerOpen()
                    ? name + " already had a cash drawer today"
                    : taken.getBusinessDate().equals(today)
                            ? name + " is already in an open cash drawer"
                            : name + " must close the cash drawer of " + taken.getBusinessDate() + " first");
        });
        CashDrawerResponse drawer = save(CashDrawer.open(
                userRepository.getReferenceById(managerId),
                users.get(request.responsibleId()),
                memberIds.stream().map(users::get).toList(),
                today,
                request.openingFloat().setScale(MONEY_SCALE),
                now));
        eventPublisher.publishEvent(new CashDrawerAssignedEvent(drawer.id(), memberIds,
                users.get(request.responsibleId()).getName(), managerId, drawer.openingFloat()));
        return drawer;
    }

    // Part of the sale transaction: the drawer the seller is in on the sale's local day must be open.
    // The sale records it, so its cash counts in that drawer.
    @Transactional(propagation = Propagation.MANDATORY)
    public CashDrawer requireOpenForSale(Long userId, Instant saleDate) {
        CashDrawer drawer = lockDrawerOfDay(userId, saleDate)
                .orElseThrow(() -> new ConflictException("Open your cash drawer before selling"));
        if (!drawer.isOpen()) {
            throw new ConflictException("Your cash drawer for today is closed");
        }
        return drawer;
    }

    // Part of the cancellation transaction: the refund comes out of the drawer the sale's seller is in
    // today, waiting for a closing of it in progress. Empty if they have none open: the refund then
    // shows only in the day's cash closing.
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CashDrawer> lockForCancellation(Long sellerId, Instant at) {
        return lockDrawerOfDay(sellerId, at).filter(CashDrawer::isOpen);
    }

    // The responsible or a manager closes it; other members can see it but not close it. Expected cash
    // is computed after the lock, once every sale and cancellation holding the drawer has committed;
    // past days can be closed late.
    @Transactional
    public CashDrawerResponse close(Long id, CloseCashDrawerRequest request, CurrentUser actor) {
        // Without a lock (projections, so no stale entity stays in the persistence context): probing
        // other drawers' ids must not block their sales.
        Long responsibleId = cashDrawerRepository.findResponsibleIdById(id).orElseThrow(CashDrawerService::notFound);
        if (!actor.isManager() && !responsibleId.equals(actor.id())) {
            if (memberRepository.existsByCashDrawerIdAndUserId(id, actor.id())) {
                throw new ForbiddenException("Only the drawer's responsible or a manager can close it");
            }
            throw notFound();
        }
        CashDrawer drawer = cashDrawerRepository.findByIdForUpdate(id).orElseThrow(CashDrawerService::notFound);
        if (!drawer.isOpen()) {
            throw new ConflictException("Cash drawer is already closed");
        }
        drawer.close(
                userRepository.getReferenceById(actor.id()),
                now(),
                drawer.getOpeningFloat().add(saleRepository.drawerNetCash(id)).setScale(MONEY_SCALE),
                request.countedCash().setScale(MONEY_SCALE),
                request.note());
        eventPublisher.publishEvent(new CashDrawerClosedEvent(id, responsibleId, actor.id(), drawer.getBusinessDate(),
                drawer.getCountedCash().subtract(drawer.getExpectedCash())));
        return CashDrawerResponse.from(drawer);
    }

    // The open drawer the user is a member of.
    @Transactional(readOnly = true)
    public CashDrawerResponse findCurrent(Long userId) {
        return memberRepository.findOpenDrawerIdByUser(userId)
                .flatMap(cashDrawerRepository::findDetailedById)
                .map(CashDrawerResponse::from)
                .orElseThrow(() -> new NotFoundException("No open cash drawer"));
    }

    // A seller asking for a drawer they aren't in gets 404, like sales.
    @Transactional(readOnly = true)
    public CashDrawerResponse findById(Long id, CurrentUser viewer) {
        return cashDrawerRepository.findDetailedById(id)
                .filter(found -> isVisibleTo(found, viewer))
                .map(CashDrawerResponse::from)
                .orElseThrow(CashDrawerService::notFound);
    }

    @Transactional(readOnly = true)
    public Page<CashDrawerResponse> search(CashDrawerSearch search, Pageable pageable, CurrentUser viewer) {
        CashDrawerSearch scoped = viewer.isManager() ? search : search.withSeller(viewer.id());
        Pageable sorted = Sorting.apply(pageable, SORTABLE, DEFAULT_SORT);
        Page<CashDrawer> page = cashDrawerRepository.findAll(scoped.toSpecification(), sorted);
        Map<Long, List<Ref>> members = membersOf(page.getContent());
        return page.map(drawer -> CashDrawerResponse.from(drawer, members.get(drawer.getId())));
    }

    // The cash closing's drawers of one day, in opening order; with sellerId, only the ones they're in.
    // Joins the caller's transaction, so it reads the same snapshot as the rest of the closing.
    @Transactional(readOnly = true)
    public List<CashDrawerResponse> findByDay(LocalDate day, Long sellerId) {
        List<CashDrawer> drawers = sellerId == null
                ? cashDrawerRepository.findByBusinessDateOrderById(day)
                : cashDrawerRepository.findByBusinessDateAndMember(day, sellerId);
        Map<Long, List<Ref>> members = membersOf(drawers);
        return drawers.stream().map(drawer -> CashDrawerResponse.from(drawer, members.get(drawer.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<AssignableSellerResponse> findAssignableSellers() {
        return memberRepository.findAssignableSellers(today(now())).stream()
                .map(AssignableSellerResponse::from)
                .toList();
    }

    // Members never change and the drawer row is locked FOR SHARE, so the lookup itself needs no lock.
    private Optional<CashDrawer> lockDrawerOfDay(Long userId, Instant at) {
        return memberRepository.findDrawerIdByUserAndDay(userId, today(at))
                .flatMap(cashDrawerRepository::findByIdForShare);
    }

    // A concurrent open with a shared member fails on uk_cash_drawer_member_user_date or
    // uk_cash_drawer_member_open_user (409).
    private CashDrawerResponse save(CashDrawer drawer) {
        return CashDrawerResponse.from(cashDrawerRepository.saveAndFlush(drawer));
    }

    // Each drawer's members by name, in one query.
    private Map<Long, List<Ref>> membersOf(List<CashDrawer> drawers) {
        if (drawers.isEmpty()) {
            return Map.of();
        }
        return memberRepository.findWithUserByDrawerIds(drawers.stream().map(CashDrawer::getId).toList()).stream()
                .collect(Collectors.groupingBy(
                        member -> member.getId().getCashDrawerId(),
                        Collectors.mapping(member -> CashDrawerResponse.ref(member.getUser()), Collectors.toList())));
    }

    private static boolean isVisibleTo(CashDrawer drawer, CurrentUser viewer) {
        return viewer.isManager()
                || drawer.getMembers().stream().anyMatch(member -> member.getUser().getId().equals(viewer.id()));
    }

    // PostgreSQL stores microseconds; truncating keeps responses equal to later reads.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDate today(Instant at) {
        return LocalDate.ofInstant(at, storeProperties.timeZone());
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Cash drawer not found");
    }
}
