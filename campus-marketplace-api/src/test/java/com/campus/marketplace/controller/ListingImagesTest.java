package com.campus.marketplace.controller;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.ImageStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ListingImagesTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Autowired private MockMvc mockMvc;
    @Autowired private ListingRepository listingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private ImageStorageService imageStorage;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Listing listing;
    private final AtomicInteger uploads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        listingRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder().fullName("Alice").email("alice@seu.edu.bd").password("x").role(Role.STUDENT).enabled(true).build());
        User bob = userRepository.save(User.builder().fullName("Bob").email("bob@seu.edu.bd").password("x").role(Role.STUDENT).enabled(true).build());
        aliceToken = jwtService.generateAccessToken(UserPrincipal.from(alice));
        bobToken = jwtService.generateAccessToken(UserPrincipal.from(bob));
        listing = listingRepository.save(Listing.builder().title("Lamp").price(new BigDecimal("10.00"))
                .category(Category.OTHER).status(ListingStatus.ACTIVE).owner(alice).build());
        uploads.set(0);
        when(imageStorage.upload(any())).thenAnswer(inv -> {
            int n = uploads.incrementAndGet();
            return new ImageStorageService.UploadedImage("https://cdn.example.com/p" + n + ".png", "pid" + n);
        });
    }

    private org.springframework.test.web.servlet.ResultActions add(String token) throws Exception {
        return mockMvc.perform(multipart("/api/listings/{id}/images", listing.getId())
                .file(new MockMultipartFile("image", "a.png", "image/png", PNG)).header("Authorization", "Bearer " + token));
    }

    private JsonNode addOk() throws Exception {
        return objectMapper.readTree(add(aliceToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void addingPhotos_keepsOrder_andFirstIsTheCover() throws Exception {
        addOk();
        JsonNode body = addOk();
        assertThat(body.get("images")).hasSize(2);
        assertThat(body.get("images").get(0).get("url").asText()).isEqualTo("https://cdn.example.com/p1.png");
        assertThat(body.get("imageUrl").asText()).isEqualTo("https://cdn.example.com/p1.png");
        mockMvc.perform(get("/api/listings/{id}", listing.getId()))
                .andExpect(jsonPath("$.images.length()").value(2));
    }

    @Test
    void aSixthPhoto_isRejected_withoutUploading() throws Exception {
        for (int i = 0; i < 5; i++) addOk();
        add(aliceToken).andExpect(status().isConflict());
        assertThat(uploads.get()).isEqualTo(5);
    }

    @Test
    void onlyTheOwner_canAdd_remove_orReorder() throws Exception {
        add(bobToken).andExpect(status().isForbidden());
        verify(imageStorage, never()).upload(any());
        JsonNode body = addOk();
        long id = body.get("images").get(0).get("id").asLong();
        mockMvc.perform(delete("/api/listings/{id}/images/{img}", listing.getId(), id).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/listings/{id}/images/order", listing.getId()).header("Authorization", "Bearer " + bobToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[" + id + "]}")).andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/listings/{id}/images", listing.getId())
                .file(new MockMultipartFile("image", "a.png", "image/png", PNG))).andExpect(status().isUnauthorized());
    }

    @Test
    void reorder_changesTheCover_andRejectsAWrongSet() throws Exception {
        addOk();
        JsonNode two = addOk();
        long first = two.get("images").get(0).get("id").asLong();
        long second = two.get("images").get(1).get("id").asLong();
        mockMvc.perform(put("/api/listings/{id}/images/order", listing.getId()).header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[" + second + "," + first + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://cdn.example.com/p2.png"))
                .andExpect(jsonPath("$.images[0].id").value(second));
        mockMvc.perform(put("/api/listings/{id}/images/order", listing.getId()).header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[" + second + "]}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/listings/{id}/images/order", listing.getId()).header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[" + second + "," + second + "]}")).andExpect(status().isBadRequest());
    }

    @Test
    void removing_deletesTheFile_promotesTheNextCover_andClearsWhenEmpty() throws Exception {
        addOk();
        JsonNode two = addOk();
        long first = two.get("images").get(0).get("id").asLong();
        long second = two.get("images").get(1).get("id").asLong();
        mockMvc.perform(delete("/api/listings/{id}/images/{img}", listing.getId(), first).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.imageUrl").value("https://cdn.example.com/p2.png"));
        verify(imageStorage).delete("pid1");
        mockMvc.perform(delete("/api/listings/{id}/images/{img}", listing.getId(), second).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").isEmpty());
        verify(imageStorage).delete("pid2");
        mockMvc.perform(delete("/api/listings/{id}/images/{img}", listing.getId(), second).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void multipartCreate_acceptsUpToFivePhotos() throws Exception {
        var req = multipart("/api/listings");
        req.param("title", "Desk").param("price", "5").param("category", "OTHER").header("Authorization", "Bearer " + aliceToken);
        for (int i = 0; i < 3; i++) req.file(new MockMultipartFile("images", "a.png", "image/png", PNG));
        mockMvc.perform(req).andExpect(status().isCreated())
                .andExpect(jsonPath("$.images.length()").value(3))
                .andExpect(jsonPath("$.imageUrl").value("https://cdn.example.com/p1.png"));

        var six = multipart("/api/listings");
        six.param("title", "Desk").param("price", "5").param("category", "OTHER").header("Authorization", "Bearer " + aliceToken);
        for (int i = 0; i < 6; i++) six.file(new MockMultipartFile("images", "a.png", "image/png", PNG));
        mockMvc.perform(six).andExpect(status().isBadRequest());
    }

    @Test
    void deletingTheListing_removesItsFiles() throws Exception {
        addOk();
        addOk();
        mockMvc.perform(delete("/api/listings/{id}", listing.getId()).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        verify(imageStorage).delete("pid1");
        verify(imageStorage).delete("pid2");
    }

    @Test
    void aLegacyListingWithOnlyAnImageUrl_keepsItAsThePhotoWhenAnotherIsAdded() throws Exception {
        listing.setImageUrl("https://old.example.com/cover.jpg");
        listingRepository.save(listing);
        JsonNode body = addOk();
        assertThat(body.get("images")).hasSize(2);
        assertThat(body.get("images").get(0).get("url").asText()).isEqualTo("https://old.example.com/cover.jpg");
        assertThat(body.get("imageUrl").asText()).isEqualTo("https://old.example.com/cover.jpg");
    }
}
