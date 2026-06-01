package com.order.processing.product.repository;

import com.order.processing.product.AbstractIntegrationTest;
import com.order.processing.product.entity.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link ProductRepository} against a real PostgreSQL
 * container.  Tests verify JPA mappings, constraints, and custom query methods.
 */
@DisplayName("ProductRepository — Integration Tests")
class ProductRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
    }

    // =========================================================================
    // save / findById
    // =========================================================================

    @Nested
    @DisplayName("save and findById")
    class SaveAndFind {

        @Test
        @DisplayName("persists a product and auto-generates id, timestamps, version=0")
        void save_newProduct_populatesAuditFieldsAndVersion() {
            Product product = buildProduct("SKU-SAVE", "Save Test", "99.99", 10);

            Product saved = productRepository.save(product);

            assertThat(saved.getId()).isNotNull().isPositive();
            assertThat(saved.getVersion()).isEqualTo(0L);
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("findById returns the saved product")
        void findById_existingProduct_returnsFull() {
            Product saved = productRepository.save(buildProduct("SKU-FIND", "Find Me", "50.00", 5));

            Optional<Product> found = productRepository.findById(saved.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getSku()).isEqualTo("SKU-FIND");
            assertThat(found.get().getName()).isEqualTo("Find Me");
            assertThat(found.get().getPrice()).isEqualByComparingTo("50.00");
            assertThat(found.get().getStockQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("findById returns empty for non-existent id")
        void findById_nonExistent_returnsEmpty() {
            Optional<Product> found = productRepository.findById(Long.MAX_VALUE);
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("version is incremented after update")
        void save_update_incrementsVersion() {
            Product saved = productRepository.save(buildProduct("SKU-VER", "Versioned", "10.00", 20));
            assertThat(saved.getVersion()).isEqualTo(0L);

            saved.setStockQuantity(15);
            Product updated = productRepository.save(saved);

            assertThat(updated.getVersion()).isEqualTo(1L);
        }
    }

    // =========================================================================
    // findBySku / existsBySku
    // =========================================================================

    @Nested
    @DisplayName("findBySku and existsBySku")
    class SkuQueries {

        @Test
        @DisplayName("findBySku returns product for known SKU")
        void findBySku_known_returnsProduct() {
            productRepository.save(buildProduct("SKU-KNOWN", "Known", "20.00", 3));

            Optional<Product> found = productRepository.findBySku("SKU-KNOWN");

            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("Known");
        }

        @Test
        @DisplayName("findBySku returns empty for unknown SKU")
        void findBySku_unknown_returnsEmpty() {
            Optional<Product> found = productRepository.findBySku("NO-SUCH-SKU");
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("existsBySku returns true for existing SKU")
        void existsBySku_existing_returnsTrue() {
            productRepository.save(buildProduct("SKU-EXISTS", "Exists", "30.00", 10));
            assertThat(productRepository.existsBySku("SKU-EXISTS")).isTrue();
        }

        @Test
        @DisplayName("existsBySku returns false for non-existent SKU")
        void existsBySku_nonExistent_returnsFalse() {
            assertThat(productRepository.existsBySku("SKU-GHOST")).isFalse();
        }
    }

    // =========================================================================
    // findAll
    // =========================================================================

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        @DisplayName("returns empty list when table is empty")
        void findAll_emptyTable_returnsEmptyList() {
            assertThat(productRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("returns all saved products")
        void findAll_multipleProducts_returnsAll() {
            productRepository.save(buildProduct("SKU-1", "One",   "10.00", 1));
            productRepository.save(buildProduct("SKU-2", "Two",   "20.00", 2));
            productRepository.save(buildProduct("SKU-3", "Three", "30.00", 3));

            List<Product> all = productRepository.findAll();

            assertThat(all).hasSize(3)
                    .extracting(Product::getSku)
                    .containsExactlyInAnyOrder("SKU-1", "SKU-2", "SKU-3");
        }
    }

    // =========================================================================
    // DB constraints
    // =========================================================================

    @Nested
    @DisplayName("Database constraints")
    class Constraints {

        @Test
        @DisplayName("throws on duplicate SKU — unique constraint enforced at DB level")
        void save_duplicateSku_throwsDataIntegrityViolation() {
            productRepository.save(buildProduct("SKU-DUPE", "Original", "10.00", 5));

            Product duplicate = buildProduct("SKU-DUPE", "Duplicate", "20.00", 3);

            assertThatThrownBy(() -> productRepository.saveAndFlush(duplicate))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("description is nullable — saves without it")
        void save_nullDescription_succeeds() {
            Product product = Product.builder()
                    .sku("SKU-NULL-DESC")
                    .name("No Description")
                    .price(new BigDecimal("5.00"))
                    .stockQuantity(1)
                    .build();

            Product saved = productRepository.saveAndFlush(product);

            assertThat(saved.getDescription()).isNull();
        }
    }

    // =========================================================================
    // deleteAll
    // =========================================================================

    @Nested
    @DisplayName("deleteAll")
    class DeleteAll {

        @Test
        @DisplayName("removes all records from the table")
        void deleteAll_clearsTable() {
            productRepository.save(buildProduct("SKU-DEL-1", "Del1", "1.00", 1));
            productRepository.save(buildProduct("SKU-DEL-2", "Del2", "2.00", 2));

            productRepository.deleteAll();

            assertThat(productRepository.findAll()).isEmpty();
            assertThat(productRepository.count()).isZero();
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private Product buildProduct(String sku, String name, String price, int stock) {
        return Product.builder()
                .sku(sku)
                .name(name)
                .price(new BigDecimal(price))
                .stockQuantity(stock)
                .build();
    }
}
