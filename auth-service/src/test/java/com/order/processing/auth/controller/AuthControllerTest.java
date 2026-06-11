package com.order.processing.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link AuthController}.
 *
 * <p>Starts the full application context (with a random-ish but deterministic
 * signing key from application.yml) and verifies HTTP contracts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login with valid admin credentials returns 200 + JWT")
    void login_validAdminCredentials_returns200WithToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andReturn();

        // The token should be a three-part JWT: header.payload.signature
        String body  = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        String token = json.get("token").asText();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("JWT payload contains uid claim with the user's numeric ID")
    void login_issuedJwt_containsUidClaim() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("token").asText();

        // Decode the middle (payload) segment without verifying the signature
        String[] parts = token.split("\\.");
        String payloadJson = new String(
                java.util.Base64.getUrlDecoder().decode(parts[1]));
        JsonNode payload = objectMapper.readTree(payloadJson);

        assertThat(payload.has("uid"))
                .as("JWT payload must contain a 'uid' claim")
                .isTrue();
        assertThat(payload.get("uid").isNumber())
                .as("'uid' claim must be numeric")
                .isTrue();
        assertThat(payload.get("uid").asLong())
                .as("admin's uid must be 1")
                .isEqualTo(1L);
        assertThat(payload.get("sub").asText())
                .as("sub claim must be the username")
                .isEqualTo("admin");
    }

    @Test
    @DisplayName("POST /auth/login with valid user credentials returns 200 + JWT")
    void login_validUserCredentials_returns200WithToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"user\",\"password\":\"userpassword\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // ── Bad credentials ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login with wrong password returns 401")
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("POST /auth/login with unknown username returns 401")
    void login_unknownUser_returns401() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hacker\",\"password\":\"secret\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login with blank username returns 400")
    void login_blankUsername_returns400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"secret\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/login with missing body returns 400")
    void login_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
