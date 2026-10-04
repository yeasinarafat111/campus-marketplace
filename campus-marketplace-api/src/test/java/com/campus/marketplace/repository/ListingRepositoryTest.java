package com.campus.marketplace.repository;

import com.campus.marketplace.dto.ListingSearchCriteria;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ListingRepositoryTest {

    @Autowired private ListingRepository listingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().fullName("Alice").email("alice@seu.edu.bd").password("x").build());
        bob = userRepository.save(User.builder().fullName("Bob").email("bob@seu.edu.bd").password("x").build());

        save("Calculus Textbook", Category.BOOKS, "450.00", ListingStatus.ACTIVE, alice);
        save("Physics textbook (used)", Category.BOOKS, "300.00", ListingStatus.ACTIVE, bob);
        save("Scientific Calculator", Category.ELECTRONICS, "1200.00", ListingStatus.ACTIVE, alice);
        save("Study Desk", Category.FURNITURE, "2500.00", ListingStatus.SOLD, bob);
        save("100% cotton hoodie", Category.CLOTHING, "800.00", ListingStatus.ACTIVE, alice);
        save("USB_C cable", Category.ELECTRONICS, "150.00", ListingStatus.ACTIVE, bob);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void noCriteria_returnsEverything() {
        assertThat(search(criteria(null, null, null, null, null), PageRequest.of(0, 50)).getTotalElements()).isEqualTo(6);
    }

    @Test
    void keyword_isCaseInsensitiveSubstringOfTitle() {
        assertThat(titles(search(criteria("TEXTBOOK", null, null, null, null))))
                .containsExactlyInAnyOrder("Calculus Textbook", "Physics textbook (used)");
        assertThat(titles(search(criteria("calc", null, null, null, null))))
                .containsExactlyInAnyOrder("Calculus Textbook", "Scientific Calculator");
    }

    @Test
    void blankKeyword_isIgnored() {
        assertThat(search(criteria("   ", null, null, null, null), PageRequest.of(0, 50)).getTotalElements()).isEqualTo(6);
    }

    @Test
    void likeWildcardsInKeyword_areMatchedLiterally() {
        assertThat(titles(search(criteria("%", null, null, null, null)))).containsExactly("100% cotton hoodie");
        assertThat(titles(search(criteria("_", null, null, null, null)))).containsExactly("USB_C cable");
    }

    @Test
    void category_filters() {
        assertThat(titles(search(criteria(null, Category.ELECTRONICS, null, null, null))))
                .containsExactlyInAnyOrder("Scientific Calculator", "USB_C cable");
    }

    @Test
    void priceRange_isInclusive() {
        assertThat(titles(search(criteria(null, null, new BigDecimal("300"), new BigDecimal("800"), null))))
                .containsExactlyInAnyOrder("Calculus Textbook", "Physics textbook (used)", "100% cotton hoodie");
        assertThat(titles(search(criteria(null, null, new BigDecimal("1200"), null, null))))
                .containsExactlyInAnyOrder("Scientific Calculator", "Study Desk");
        assertThat(titles(search(criteria(null, null, null, new BigDecimal("150"), null))))
                .containsExactly("USB_C cable");
    }

    @Test
    void status_filters() {
        assertThat(titles(search(criteria(null, null, null, null, ListingStatus.SOLD)))).containsExactly("Study Desk");
        assertThat(search(criteria(null, null, null, null, ListingStatus.ACTIVE), PageRequest.of(0, 50)).getTotalElements())
                .isEqualTo(5);
    }

    @Test
    void allFiltersCombine() {
        assertThat(titles(search(criteria("book", Category.BOOKS, new BigDecimal("400"), new BigDecimal("500"), ListingStatus.ACTIVE))))
                .containsExactly("Calculus Textbook");
    }

    @Test
    void pagination_andSorting() {
        Pageable firstPage = PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "price"));
        Page<Listing> page = search(criteria(null, null, null, null, ListingStatus.ACTIVE), firstPage);

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(titles(page)).containsExactly("USB_C cable", "Physics textbook (used)");

        Page<Listing> last = search(criteria(null, null, null, null, ListingStatus.ACTIVE), firstPage.withPage(2));
        assertThat(titles(last)).containsExactly("Scientific Calculator");
    }

    @Test
    void ownerActive_excludesDeactivatedOwnersListings() {
        User reloadedBob = userRepository.findById(bob.getId()).orElseThrow();
        reloadedBob.setActive(false);
        userRepository.saveAndFlush(reloadedBob);
        entityManager.clear();

        var spec = ListingSpecifications.matching(criteria(null, null, null, null, null))
                .and(ListingSpecifications.ownerActive());
        Page<Listing> page = listingRepository.findAll(spec, PageRequest.of(0, 50));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).allMatch(l -> l.getOwner().getId().equals(alice.getId()));
    }

    @Test
    void searchResults_haveOwnerLoaded() {
        Page<Listing> page = search(criteria("desk", null, null, null, null));

        Listing desk = page.getContent().get(0);
        assertThat(Hibernate.isInitialized(desk.getOwner())).isTrue();
        assertThat(desk.getOwner().getEmail()).isEqualTo("bob@seu.edu.bd");
    }

    @Test
    void findByOwner_pages() {
        Page<Listing> page = listingRepository.findByOwner(alice, PageRequest.of(0, 10, Sort.by("title")));
        assertThat(titles(page)).containsExactly("100% cotton hoodie", "Calculus Textbook", "Scientific Calculator");
    }

    @Test
    void defaults_andTimestamp() {
        Listing listing = listingRepository.saveAndFlush(Listing.builder()
                .title("Lamp").price(new BigDecimal("199.99")).category(Category.OTHER).owner(alice).build());
        entityManager.clear();

        Listing found = listingRepository.findById(listing.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getPrice()).isEqualByComparingTo("199.99");
    }

    private Page<Listing> search(ListingSearchCriteria criteria) {
        return search(criteria, PageRequest.of(0, 50));
    }

    private Page<Listing> search(ListingSearchCriteria criteria, Pageable pageable) {
        return listingRepository.findAll(ListingSpecifications.matching(criteria), pageable);
    }

    private static ListingSearchCriteria criteria(String keyword, Category category, BigDecimal min, BigDecimal max,
                                                  ListingStatus status) {
        return new ListingSearchCriteria(keyword, category, min, max, status);
    }

    private static java.util.List<String> titles(Page<Listing> page) {
        return page.getContent().stream().map(Listing::getTitle).toList();
    }

    private void save(String title, Category category, String price, ListingStatus status, User owner) {
        listingRepository.save(Listing.builder()
                .title(title)
                .description("desc")
                .price(new BigDecimal(price))
                .category(category)
                .status(status)
                .owner(owner)
                .build());
    }
}
