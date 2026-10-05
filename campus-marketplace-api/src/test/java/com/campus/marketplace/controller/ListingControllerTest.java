package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.ImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ListingControllerTest {

    private static final String VALID_BODY = """
            {"title":"Calculus Textbook","description":"Good condition","price":450.50,
             "category":"BOOKS","imageUrl":"https://img.example.com/calc.jpg"}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private ListingRepository listingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @MockitoBean private ImageStorageService imageStorage;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final String CDN_URL = "https://res.cloudinary.com/demo/image/upload/v1/listings/abc.png";

    private User alice;
    private User bob;
    private User admin;
    private String aliceToken;
    private String bobToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        listingRepository.deleteAll();
        userRepository.deleteAll();
        alice = saveUser("alice@seu.edu.bd", "Alice", Role.STUDENT);
        bob = saveUser("bob@seu.edu.bd", "Bob", Role.STUDENT);
        admin = saveUser("admin@seu.edu.bd", "Admin", Role.ADMIN);
        aliceToken = token(alice);
        bobToken = token(bob);
        adminToken = token(admin);
    }

    // ---- GET /api/listings ----

    @Test
    void search_isPublic_andShowsOnlyActiveListings() throws Exception {
        saveListing("Desk", Category.FURNITURE, "2500", ListingStatus.SOLD, bob);
        saveListing("Lamp", Category.FURNITURE, "300", ListingStatus.ACTIVE, bob);

        mockMvc.perform(get("/api/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Lamp"))
                .andExpect(jsonPath("$.content[0].owner.fullName").value("Bob"))
                .andExpect(jsonPath("$.content[0].owner.email").doesNotExist());
    }

    @Test
    void search_appliesFiltersPaginationAndSort() throws Exception {
        saveListing("Calculus Textbook", Category.BOOKS, "450", ListingStatus.ACTIVE, alice);
        saveListing("Physics Textbook", Category.BOOKS, "300", ListingStatus.ACTIVE, bob);
        saveListing("Chemistry Textbook", Category.BOOKS, "600", ListingStatus.ACTIVE, bob);
        saveListing("Calculator", Category.ELECTRONICS, "1200", ListingStatus.ACTIVE, alice);

        mockMvc.perform(get("/api/listings")
                        .param("keyword", "textbook").param("category", "BOOKS")
                        .param("minPrice", "300").param("maxPrice", "600")
                        .param("sort", "price").param("direction", "asc")
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.content[0].title").value("Physics Textbook"))
                .andExpect(jsonPath("$.content[1].title").value("Calculus Textbook"));
    }

    @Test
    void search_withoutSort_putsCurrentlyFeaturedListingsFirst_thenNewest() throws Exception {
        saveListing("Old plain", Category.BOOKS, "10", ListingStatus.ACTIVE, alice);
        Listing expired = saveListing("Expired feature", Category.BOOKS, "10", ListingStatus.ACTIVE, alice);
        feature(expired, Instant.now().minus(Duration.ofDays(1)));
        Listing featured = saveListing("Featured but older", Category.BOOKS, "10", ListingStatus.ACTIVE, bob);
        feature(featured, Instant.now().plus(Duration.ofDays(3)));
        saveListing("New plain", Category.BOOKS, "10", ListingStatus.ACTIVE, bob);

        mockMvc.perform(get("/api/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].title").value("Featured but older"))
                .andExpect(jsonPath("$.content[0].featured").value(true))
                // An expired feature no longer pins the listing: the rest is newest first.
                .andExpect(jsonPath("$.content[1].title").value("New plain"))
                .andExpect(jsonPath("$.content[2].title").value("Expired feature"))
                .andExpect(jsonPath("$.content[3].title").value("Old plain"));
    }

    @Test
    void search_featuredFirstOrderSurvivesPaging() throws Exception {
        for (int i = 1; i <= 3; i++) {
            saveListing("Plain " + i, Category.BOOKS, "10", ListingStatus.ACTIVE, alice);
        }
        Listing featured = saveListing("The featured one", Category.BOOKS, "10", ListingStatus.ACTIVE, bob);
        feature(featured, Instant.now().plus(Duration.ofDays(1)));

        mockMvc.perform(get("/api/listings").param("size", "2"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].title").value("The featured one"))
                .andExpect(jsonPath("$.content[1].title").value("Plain 3"));
        mockMvc.perform(get("/api/listings").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.content[0].title").value("Plain 2"))
                .andExpect(jsonPath("$.content[1].title").value("Plain 1"));
    }

    @Test
    void search_withExplicitSort_isNotPinnedToFeatured() throws Exception {
        Listing pricey = saveListing("Pricey featured", Category.BOOKS, "900", ListingStatus.ACTIVE, alice);
        feature(pricey, Instant.now().plus(Duration.ofDays(1)));
        saveListing("Cheap", Category.BOOKS, "5", ListingStatus.ACTIVE, bob);

        mockMvc.perform(get("/api/listings").param("sort", "price").param("direction", "asc"))
                .andExpect(jsonPath("$.content[0].title").value("Cheap"))
                .andExpect(jsonPath("$.content[1].title").value("Pricey featured"));
    }

    @Test
    void search_featuredFilter_returnsOnlyCurrentlyFeatured() throws Exception {
        saveListing("Plain", Category.BOOKS, "10", ListingStatus.ACTIVE, alice);
        feature(saveListing("Expired", Category.BOOKS, "10", ListingStatus.ACTIVE, alice), Instant.now().minusSeconds(60));
        feature(saveListing("Live", Category.BOOKS, "10", ListingStatus.ACTIVE, bob), Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/api/listings").param("featured", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Live"));
        // featured=false (or absent) doesn't filter.
        mockMvc.perform(get("/api/listings").param("featured", "false"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void search_ownerFilter_returnsOnlyThatSellersActiveListings() throws Exception {
        saveListing("Alice 1", Category.BOOKS, "10", ListingStatus.ACTIVE, alice);
        saveListing("Alice sold", Category.BOOKS, "10", ListingStatus.SOLD, alice);
        saveListing("Bob 1", Category.BOOKS, "10", ListingStatus.ACTIVE, bob);

        mockMvc.perform(get("/api/listings").param("ownerId", String.valueOf(alice.getId())))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Alice 1"));
    }

    @Test
    void listingResponse_exposesSellerMemberSince_butNoContactDetails() throws Exception {
        Listing listing = saveListing("Lamp", Category.FURNITURE, "300", ListingStatus.ACTIVE, bob);

        mockMvc.perform(get("/api/listings/" + listing.getId()))
                .andExpect(jsonPath("$.owner.fullName").value("Bob"))
                .andExpect(jsonPath("$.owner.memberSince").isNotEmpty())
                .andExpect(jsonPath("$.owner.email").doesNotExist())
                .andExpect(jsonPath("$.owner.phone").doesNotExist());
    }

    @Test
    void mine_reservedFilter_splitsReservedFromUnreserved() throws Exception {
        saveListing("Free to buy", Category.BOOKS, "10", ListingStatus.ACTIVE, alice);
        Listing held = saveListing("Held", Category.BOOKS, "10", ListingStatus.ACTIVE, alice);
        held.setReservedBy(bob);
        held.setReservedAt(Instant.now());
        listingRepository.save(held);

        mockMvc.perform(auth(get("/api/listings/mine").param("status", "ACTIVE").param("reserved", "true"), aliceToken))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Held"));
        mockMvc.perform(auth(get("/api/listings/mine").param("status", "ACTIVE").param("reserved", "false"), aliceToken))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Free to buy"));
        mockMvc.perform(auth(get("/api/listings/mine").param("status", "ACTIVE"), aliceToken))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void search_rejectsBadParameters() throws Exception {
        mockMvc.perform(get("/api/listings").param("sort", "owner.email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sort must be one of: createdAt, price, title"));
        mockMvc.perform(get("/api/listings").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/listings").param("minPrice", "500").param("maxPrice", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("minPrice must not be greater than maxPrice"));
        mockMvc.perform(get("/api/listings").param("category", "CARS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'category'"));
        mockMvc.perform(get("/api/listings").param("direction", "sideways"))
                .andExpect(status().isBadRequest());
    }

    // ---- GET /api/listings/{id} ----

    @Test
    void getById_isPublic() throws Exception {
        Listing listing = saveListing("Lamp", Category.FURNITURE, "300", ListingStatus.ACTIVE, bob);

        mockMvc.perform(get("/api/listings/{id}", listing.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Lamp"))
                .andExpect(jsonPath("$.owner.id").value(bob.getId()));
    }

    @Test
    void getById_missing_returns404() throws Exception {
        mockMvc.perform(get("/api/listings/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Listing 999999 not found"));
    }

    // ---- POST /api/listings ----

    @Test
    void create_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/listings").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_ownedByCurrentUser_returns201WithLocation() throws Exception {
        mockMvc.perform(json(post("/api/listings"), aliceToken, VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/listings/" + onlyListing().getId())))
                .andExpect(jsonPath("$.title").value("Calculus Textbook"))
                .andExpect(jsonPath("$.price").value(450.50))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.owner.id").value(alice.getId()));
    }

    @Test
    void create_ignoresClientSuppliedOwnerAndStatus() throws Exception {
        String body = """
                {"title":"Lamp","price":100,"category":"OTHER","status":"SOLD","owner":{"id":%d}}"""
                .formatted(bob.getId());

        mockMvc.perform(json(post("/api/listings"), aliceToken, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.owner.id").value(alice.getId()));
    }

    @Test
    void create_validatesBody() throws Exception {
        String body = """
                {"title":" ","price":-1,"category":null,"imageUrl":"javascript:alert(1)"}""";

        mockMvc.perform(json(post("/api/listings"), aliceToken, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").value("Title is required"))
                .andExpect(jsonPath("$.fieldErrors.price").value("Price cannot be negative"))
                .andExpect(jsonPath("$.fieldErrors.category").value("Category is required"))
                .andExpect(jsonPath("$.fieldErrors.imageUrl").value("Image URL must be an http:// or https:// URL"));
        assertThat(listingRepository.count()).isZero();
    }

    // ---- POST /api/listings (multipart) ----

    @Test
    void createMultipart_withImage_uploadsAndStoresSecureUrl() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "lamp.png", "image/png", PNG);
        when(imageStorage.upload(any())).thenReturn(new ImageStorageService.UploadedImage(CDN_URL, "listings/abc"));

        mockMvc.perform(auth(multipartListing("Desk lamp", "300", "FURNITURE").file(image), aliceToken))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.title").value("Desk lamp"))
                .andExpect(jsonPath("$.imageUrl").value(CDN_URL))
                .andExpect(jsonPath("$.owner.id").value(alice.getId()));
        assertThat(onlyListing().getImageUrl()).isEqualTo(CDN_URL);
    }

    @Test
    void createMultipart_uploadedImageOverridesImageUrlField() throws Exception {
        when(imageStorage.upload(any())).thenReturn(new ImageStorageService.UploadedImage(CDN_URL, "listings/abc"));

        mockMvc.perform(auth(multipartListing("Lamp", "300", "FURNITURE")
                        .file(new MockMultipartFile("image", "lamp.png", "image/png", PNG))
                        .param("imageUrl", "https://elsewhere.example.com/x.jpg"), aliceToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUrl").value(CDN_URL));
    }

    @Test
    void createMultipart_withoutImage_works() throws Exception {
        mockMvc.perform(auth(multipartListing("Lamp", "300", "FURNITURE"), aliceToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUrl").doesNotExist());
        verify(imageStorage, never()).upload(any());
    }

    @Test
    void createMultipart_validatesFields_beforeUploading() throws Exception {
        mockMvc.perform(auth(multipartListing(" ", "-5", "FURNITURE")
                        .file(new MockMultipartFile("image", "lamp.png", "image/png", PNG)), aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").value("Title is required"))
                .andExpect(jsonPath("$.fieldErrors.price").value("Price cannot be negative"));
        verify(imageStorage, never()).upload(any());
    }

    @Test
    void createMultipart_unparseablePrice_returnsCleanFieldError() throws Exception {
        // A conversion failure stops the record from being built, so only this error is reported.
        mockMvc.perform(auth(multipartListing("Lamp", "abc", "FURNITURE"), aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.price").value("Invalid value"));
        verify(imageStorage, never()).upload(any());
    }

    @Test
    void createMultipart_requiresAuthentication_andDoesNotUpload() throws Exception {
        mockMvc.perform(multipartListing("Lamp", "300", "FURNITURE")
                        .file(new MockMultipartFile("image", "lamp.png", "image/png", PNG)))
                .andExpect(status().isUnauthorized());
        verify(imageStorage, never()).upload(any());
    }

    @Test
    void createMultipart_rejectedImage_returns400_andSavesNothing() throws Exception {
        when(imageStorage.upload(any())).thenThrow(new BadRequestException("Only JPEG, PNG, WEBP or GIF images are allowed"));

        mockMvc.perform(auth(multipartListing("Lamp", "300", "FURNITURE")
                        .file(new MockMultipartFile("image", "x.png", "image/png", "nope".getBytes())), aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only JPEG, PNG, WEBP or GIF images are allowed"));
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    void createMultipart_tooLarge_returns413() throws Exception {
        when(imageStorage.upload(any())).thenThrow(new MaxUploadSizeExceededException(5 * 1024 * 1024));

        mockMvc.perform(auth(multipartListing("Lamp", "300", "FURNITURE")
                        .file(new MockMultipartFile("image", "big.png", "image/png", PNG)), aliceToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("Uploaded file is too large"));
    }

    @Test
    void createMultipart_whenSavingFails_deletesTheUploadedImage() throws Exception {
        when(imageStorage.upload(any())).thenReturn(new ImageStorageService.UploadedImage(CDN_URL, "listings/abc"));
        // Authenticated as a user id with no row, so saving the listing fails after the upload succeeded.
        UserPrincipal missing = new UserPrincipal(999_999L, "missing@seu.edu.bd", "x", Role.STUDENT, true);

        mockMvc.perform(multipartListing("Lamp", "300", "FURNITURE")
                        .file(new MockMultipartFile("image", "lamp.png", "image/png", PNG))
                        .with(user(missing)))
                .andExpect(status().isNotFound());

        verify(imageStorage).delete("listings/abc");
        assertThat(listingRepository.count()).isZero();
    }

    private static MockMultipartHttpServletRequestBuilder multipartListing(String title, String price, String category) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/listings");
        request.param("title", title).param("price", price).param("category", category);
        return request;
    }

    // ---- PUT /api/listings/{id} ----

    @Test
    void update_byOwner_replacesFields() throws Exception {
        Listing listing = saveListing("Old title", Category.OTHER, "100", ListingStatus.ACTIVE, alice);

        mockMvc.perform(json(put("/api/listings/{id}", listing.getId()), aliceToken, VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Calculus Textbook"))
                .andExpect(jsonPath("$.category").value("BOOKS"));
        assertThat(listingRepository.findById(listing.getId()).orElseThrow().getTitle()).isEqualTo("Calculus Textbook");
    }

    @Test
    void update_byOtherUser_orAdmin_returns403() throws Exception {
        Listing listing = saveListing("Mine", Category.OTHER, "100", ListingStatus.ACTIVE, alice);

        mockMvc.perform(json(put("/api/listings/{id}", listing.getId()), bobToken, VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can only edit your own listings"));
        mockMvc.perform(json(put("/api/listings/{id}", listing.getId()), adminToken, VALID_BODY))
                .andExpect(status().isForbidden());
        assertThat(listingRepository.findById(listing.getId()).orElseThrow().getTitle()).isEqualTo("Mine");
    }

    @Test
    void update_missing_returns404() throws Exception {
        mockMvc.perform(json(put("/api/listings/{id}", 999_999), aliceToken, VALID_BODY))
                .andExpect(status().isNotFound());
    }

    // ---- DELETE /api/listings/{id} ----

    @Test
    void delete_byOwner_returns204() throws Exception {
        Listing listing = saveListing("Lamp", Category.OTHER, "100", ListingStatus.ACTIVE, alice);

        mockMvc.perform(auth(delete("/api/listings/{id}", listing.getId()), aliceToken))
                .andExpect(status().isNoContent());
        assertThat(listingRepository.existsById(listing.getId())).isFalse();
    }

    @Test
    void delete_byOtherUser_returns403() throws Exception {
        Listing listing = saveListing("Lamp", Category.OTHER, "100", ListingStatus.ACTIVE, alice);

        mockMvc.perform(auth(delete("/api/listings/{id}", listing.getId()), bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can only delete your own listings"));
        assertThat(listingRepository.existsById(listing.getId())).isTrue();
    }

    @Test
    void delete_byAdmin_returns204() throws Exception {
        Listing listing = saveListing("Spam", Category.OTHER, "1", ListingStatus.ACTIVE, alice);

        mockMvc.perform(auth(delete("/api/listings/{id}", listing.getId()), adminToken))
                .andExpect(status().isNoContent());
        assertThat(listingRepository.existsById(listing.getId())).isFalse();
    }

    @Test
    void delete_withoutToken_returns401() throws Exception {
        Listing listing = saveListing("Lamp", Category.OTHER, "100", ListingStatus.ACTIVE, alice);

        mockMvc.perform(delete("/api/listings/{id}", listing.getId())).andExpect(status().isUnauthorized());
    }

    // ---- PATCH /api/listings/{id}/sold ----

    @Test
    void markSold_byOwner_hidesFromPublicSearch_andIsIdempotent() throws Exception {
        Listing listing = saveListing("Lamp", Category.OTHER, "100", ListingStatus.ACTIVE, alice);

        mockMvc.perform(auth(patch("/api/listings/{id}/sold", listing.getId()), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"));
        mockMvc.perform(auth(patch("/api/listings/{id}/sold", listing.getId()), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"));

        mockMvc.perform(get("/api/listings")).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/listings/{id}", listing.getId())).andExpect(jsonPath("$.status").value("SOLD"));
    }

    @Test
    void markSold_byOtherUser_returns403() throws Exception {
        Listing listing = saveListing("Lamp", Category.OTHER, "100", ListingStatus.ACTIVE, alice);

        mockMvc.perform(auth(patch("/api/listings/{id}/sold", listing.getId()), bobToken))
                .andExpect(status().isForbidden());
        assertThat(listingRepository.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    // ---- GET /api/listings/mine ----

    @Test
    void mine_requiresAuthentication_evenThoughIdRouteIsPublic() throws Exception {
        mockMvc.perform(get("/api/listings/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void mine_returnsOnlyCurrentUsersListings_includingSold() throws Exception {
        saveListing("Alice active", Category.OTHER, "100", ListingStatus.ACTIVE, alice);
        saveListing("Alice sold", Category.OTHER, "200", ListingStatus.SOLD, alice);
        saveListing("Bob active", Category.OTHER, "300", ListingStatus.ACTIVE, bob);

        mockMvc.perform(auth(get("/api/listings/mine").param("sort", "title").param("direction", "asc"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Alice active"))
                .andExpect(jsonPath("$.content[1].title").value("Alice sold"));

        mockMvc.perform(auth(get("/api/listings/mine").param("status", "SOLD"), aliceToken))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Alice sold"));
    }

    // ---- helpers ----

    private User saveUser(String email, String name, Role role) {
        return userRepository.save(User.builder()
                .fullName(name).email(email).password("x").role(role).enabled(true).build());
    }

    private String token(User user) {
        return jwtService.generateAccessToken(UserPrincipal.from(user));
    }

    private Listing saveListing(String title, Category category, String price, ListingStatus status, User owner) {
        return listingRepository.save(Listing.builder()
                .title(title).price(new BigDecimal(price)).category(category).status(status).owner(owner).build());
    }

    private void feature(Listing listing, Instant until) {
        listing.setFeaturedUntil(until);
        listingRepository.save(listing);
    }

    private Listing onlyListing() {
        assertThat(listingRepository.count()).isEqualTo(1);
        return listingRepository.findAll().get(0);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
        return auth(request, token).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
