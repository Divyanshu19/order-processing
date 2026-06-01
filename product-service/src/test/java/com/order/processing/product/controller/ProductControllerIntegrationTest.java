package com.order.processing.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.order.processing.product.AbstractIntegrationTest;
import com.order.processing.product.dto.ProductRequest;
import com.order.processing.product.dto.StockUpdateRequest;
import com.order.processing.product.entity.Product;
import com.order.processing.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests for {@link ProductController}.
 *
 * <p>Each test runs against a real PostgreSQL container (via {@link AbstractIntegrationTest})
 * with the full Spring ApplicationContext loaded — JPA, validation, exception
 * handlers and all.  {@link MockMvc} is used so we exercise the HTTP layer
 * (serialization, status codes, headers) without a real TCP socket.
 *
 * <p>Test isolation strategy: {@link BeforeEach} truncates the {@code products}
 * table so every test starts with a clean slate.
 */
@AutoConfigureMockMvc
@DisplayName("ProductController — Integration Tests")
class ProductControllerIntegrationTest extends AbstractIntegrationTest {

    // ── Injected beans ────────────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Clean the table before every test for deterministic assertions. */
    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
    }

    // =========================================================================
    // POST /products — create
    // =========================================================================

    @Nested
    @DisplayName("POST /products — create product")
    class CreateProduct {

        @Test
        @DisplayName("returns 201 and persists a valid product")
        void createProduct_validRequest_returns201() throws Exception {
            ProductRequest request = laptopRequest();

            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.sku").value("SKU-LAPTOP"))
                    .andExpect(jsonPath("$.name").value("Laptop Pro"))
                    .andExpect(jsonPath("$.price").value(1299.99))
                    .andExpect(jsonPath("$.stockQuantity").value(30))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.updatedAt").isNotEmpty());

            assertThat(productRepository.existsBySku("SKU-LAPTOP")).isTrue();
        }

        @Test
        @DisplayName("returns 409 when SKU already exists")
        void createProduct_duplicateSku_returns409() throws Exception {
            productRepository.save(buildProduct("SKU-DUP", "Existing", "99.00", 10));

            ProductRequest duplicate = ProductRequest.builder()
                    .sku("SKU-DUP")
                    .name("Another Product")
                    .price(new BigDecimal("49.00"))
                    .stockQuantity(5)
                    .build();

            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(duplicate)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(containsString("SKU-DUP")));
        }

        @Test
        @DisplayName("returns 400 when SKU is blank")
        void createProduct_blankSku_returns400() throws Exception {
            ProductRequest invalid = ProductRequest.builder()
                    .sku("")
                    .name("Valid Name")
                    .price(new BigDecimal("10.00"))
                    .stockQuantity(5)
                    .build();

            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors.sku").value("SKU is required"));
        }

        @Test
        @DisplayName("returns 400 when price is zero")
        void createProduct_zeroPrice_returns400() throws Exception {
            ProductRequest invalid = ProductRequest.builder()
                    .sku("SKU-ZERO")
                    .name("Zero Price")
                    .price(BigDecimal.ZERO)
                    .stockQuantity(5)
                    .build();

            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.price")
                            .value("Price must be greater than 0"));
        }

        @Test
        @DisplayName("returns 400 when stockQuantity is negative")
        void createProduct_negativeStock_returns400() throws Exception {
            ProductRequest invalid = ProductRequest.builder()
                    .sku("SKU-NEG")
                    .name("Negative Stock")
                    .price(new BigDecimal("10.00"))
                    .stockQuantity(-1)
                    .build();

            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.stockQuantity")
                            .value("Stock quantity must be 0 or greater"));
        }

        @Test
        @DisplayName("persists optional description when provided")
        void createProduct_withDescription_persistsDescription() throws Exception {
            ProductRequest request = ProductRequest.builder()
                    .sku("SKU-DESC")
                    .name("Described Product")
                    .description("A very detailed description")
                    .price(new BigDecimal("50.00"))
                    .stockQuantity(10)
                    .build();

            mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.description").value("A very detailed description"));
        }
    }

    // =========================================================================
    // GET /products/{id} — read by ID
    // =========================================================================

    @Nested
    @DisplayName("GET /products/{id} — get product by ID")
    class GetProductById {

        @Test
        @DisplayName("returns 200 and the correct product")
        void getById_existingId_returns200() throws Exception {
            Product saved = productRepository.save(buildProduct("SKU-GET", "Get Me", "200.00", 5));

            mockMvc.perform(get("/products/{id}", saved.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(saved.getId()))
                    .andExpect(jsonPath("$.sku").value("SKU-GET"))
                    .andExpect(jsonPath("$.name").value("Get Me"))
                    .andExpect(jsonPath("$.price").value(200.00))
                    .andExpect(jsonPath("$.stockQuantity").value(5));
        }

        @Test
        @DisplayName("returns 404 for a non-existent ID")
        void getById_nonExistentId_returns404() throws Exception {
            mockMvc.perform(get("/products/{id}", 999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value(containsString("999999")));
        }
    }

    // =========================================================================
    // GET /products — read all
    // =========================================================================

    @Nested
    @DisplayName("GET /products — list all products")
    class GetAllProducts {

        @Test
        @DisplayName("returns empty array when no products exist")
        void getAll_empty_returnsEmptyArray() throws Exception {
            mockMvc.perform(get("/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("returns all persisted products")
        void getAll_multipleProducts_returnsAll() throws Exception {
            productRepository.save(buildProduct("SKU-A", "Alpha",   "10.00", 10));
            productRepository.save(buildProduct("SKU-B", "Beta",    "20.00", 20));
            productRepository.save(buildProduct("SKU-C", "Charlie", "30.00", 30));

            mockMvc.perform(get("/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[*].sku",
                            containsInAnyOrder("SKU-A", "SKU-B", "SKU-C")));
        }

        @Test
        @DisplayName("response includes all expected fields")
        void getAll_responseShape_containsAllFields() throws Exception {
            productRepository.save(buildProduct("SKU-SHAPE", "Shape Test", "55.00", 15));

            mockMvc.perform(get("/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").isNumber())
                    .andExpect(jsonPath("$[0].sku").value("SKU-SHAPE"))
                    .andExpect(jsonPath("$[0].name").isString())
                    .andExpect(jsonPath("$[0].price").isNumber())
                    .andExpect(jsonPath("$[0].stockQuantity").isNumber())
                    .andExpect(jsonPath("$[0].version").isNumber())
                    .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                    .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
        }
    }

    // =========================================================================
    // PUT /products/{id}/stock — reduce stock
    // =========================================================================

    @Nested
    @DisplayName("PUT /products/{id}/stock — reduce stock")
    class ReduceStock {

        @Test
        @DisplayName("returns 200 and updated quantity after valid deduction")
        void reduceStock_validQuantity_returns200() throws Exception {
            Product saved = productRepository.save(buildProduct("SKU-STOCK", "Stocked", "100.00", 50));

            StockUpdateRequest request = StockUpdateRequest.builder().quantity(10).build();

            mockMvc.perform(put("/products/{id}/stock", saved.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stockQuantity").value(40))
                    .andExpect(jsonPath("$.version").value(1));   // optimistic lock bumped
        }

        @Test
        @DisplayName("correctly deducts exact remaining stock to zero")
        void reduceStock_exactAvailable_stockBecomesZero() throws Exception {
            Product saved = productRepository.save(buildProduct("SKU-ZERO-STOCK", "Last One", "50.00", 5));

            StockUpdateRequest request = StockUpdateRequest.builder().quantity(5).build();

            mockMvc.perform(put("/products/{id}/stock", saved.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stockQuantity").value(0));
        }

        @Test
        @DisplayName("returns 409 when requested quantity exceeds available stock")
        void reduceStock_exceedsAvailable_returns409() throws Exception {
            Product saved = productRepository.save(buildProduct("SKU-INSUF", "Low Stock", "100.00", 3));

            StockUpdateRequest request = StockUpdateRequest.builder().quantity(10).build();

            mockMvc.perform(put("/products/{id}/stock", saved.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(allOf(
                            containsString("requested 10"),
                            containsString("available 3"))));
        }

        @Test
        @DisplayName("returns 404 when product does not exist")
        void reduceStock_nonExistentProduct_returns404() throws Exception {
            StockUpdateRequest request = StockUpdateRequest.builder().quantity(1).build();

            mockMvc.perform(put("/products/{id}/stock", 999_999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("returns 400 when quantity is zero")
        void reduceStock_zeroQuantity_returns400() throws Exception {
            Product saved = productRepository.save(buildProduct("SKU-ZERO-Q", "Product", "10.00", 10));

            StockUpdateRequest request = StockUpdateRequest.builder().quantity(0).build();

            mockMvc.perform(put("/products/{id}/stock", saved.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.quantity")
                            .value("Quantity to reduce must be at least 1"));
        }

        @Test
        @DisplayName("stock value is durably persisted after reduction")
        void reduceStock_persistenceVerified_dbReflectsNewQuantity() throws Exception {
            Product saved = productRepository.save(buildProduct("SKU-PERSIST", "Persist Me", "75.00", 20));
            StockUpdateRequest request = StockUpdateRequest.builder().quantity(7).build();

            mockMvc.perform(put("/products/{id}/stock", saved.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // Verify DB state directly — not just the HTTP response
            Product updated = productRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.getStockQuantity()).isEqualTo(13);
            assertThat(updated.getVersion()).isEqualTo(1L);
        }
    }

    // =========================================================================
    // End-to-end CRUD flow
    // =========================================================================

    @Nested
    @DisplayName("End-to-end CRUD flow")
    class EndToEndCrud {

        @Test
        @DisplayName("create → read → reduce stock → verify — full lifecycle")
        void fullProductLifecycle() throws Exception {
            // 1. CREATE
            String createResponse = mockMvc.perform(post("/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(laptopRequest())))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            Long id = objectMapper.readTree(createResponse).get("id").asLong();
            assertThat(id).isPositive();

            // 2. READ by ID
            mockMvc.perform(get("/products/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sku").value("SKU-LAPTOP"))
                    .andExpect(jsonPath("$.stockQuantity").value(30));

            // 3. LIST ALL — must contain exactly the one product
            mockMvc.perform(get("/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));

            // 4. REDUCE STOCK
            mockMvc.perform(put("/products/{id}/stock", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    StockUpdateRequest.builder().quantity(5).build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stockQuantity").value(25));

            // 5. VERIFY final state via READ
            mockMvc.perform(get("/products/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stockQuantity").value(25))
                    .andExpect(jsonPath("$.version").value(1));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Standard laptop product request used across multiple tests. */
    private ProductRequest laptopRequest() {
        return ProductRequest.builder()
                .sku("SKU-LAPTOP")
                .name("Laptop Pro")
                .description("High-performance laptop")
                .price(new BigDecimal("1299.99"))
                .stockQuantity(30)
                .build();
    }

    /** Builds and returns a {@link Product} entity (not yet persisted). */
    private Product buildProduct(String sku, String name, String price, int stock) {
        return Product.builder()
                .sku(sku)
                .name(name)
                .price(new BigDecimal(price))
                .stockQuantity(stock)
                .build();
    }
}
