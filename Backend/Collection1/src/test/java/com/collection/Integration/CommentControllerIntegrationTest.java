package com.collection.Integration;

import com.collection.config.security.JwtUtil;
import com.collection.domain.Comment;
import com.collection.usecase.comment.AddCommentUseCase;
import com.collection.usecase.comment.DeleteCommentUseCase;
import com.collection.usecase.comment.GetCommentsByMediaUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = {
                com.collection.controller.CommentController.class,
                com.collection.config.GlobalExceptionHandler.class
        },
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.collection.config.SecurityConfig.class
                ),
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.collection.config.security.JwtAuthenticationFilter.class
                )
        }
)
class CommentControllerIntegrationTest {

    @Autowired private MockMvc       mockMvc;
    @Autowired private ObjectMapper  objectMapper;

    @MockitoBean private AddCommentUseCase         addCommentUseCase;
    @MockitoBean private DeleteCommentUseCase      deleteCommentUseCase;
    @MockitoBean private GetCommentsByMediaUseCase getCommentsByMediaUseCase;
    @MockitoBean private JwtUtil                   jwtUtil;

    private Comment fakeComment;
    private Comment fakeExternalComment;

    private static final String BODY_INTERNAL =
            "{\"mediaId\":\"media-001\",\"content\":\"Super film !\"}";
    private static final String BODY_EXTERNAL =
            "{\"mediaId\":\"tmdb-movie-550\",\"content\":\"Super film !\"}";
    private static final String BODY_BLANK =
            "{\"mediaId\":\"media-001\",\"content\":\"\"}";

    @BeforeEach
    void setUp() {
        fakeComment         = new Comment("cmt-001", "user-72", "media-001",      "Super film !");
        fakeExternalComment = new Comment("cmt-002", "user-72", "tmdb-movie-550", "Super film !");
    }


    @Test
    void getByMedia_shouldReturn200WithList() throws Exception {
        Comment second = new Comment("cmt-003", "user-99", "media-001", "Pas mal");
        when(getCommentsByMediaUseCase.execute("media-001"))
                .thenReturn(List.of(fakeComment, second));

        mockMvc.perform(get("/api/comments").param("mediaId", "media-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("cmt-001"))
                .andExpect(jsonPath("$[1].id").value("cmt-003"));
    }

    @Test
    void getByMedia_emptyList_shouldReturn200() throws Exception {
        when(getCommentsByMediaUseCase.execute("media-001")).thenReturn(List.of());

        mockMvc.perform(get("/api/comments").param("mediaId", "media-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getByMedia_missingParam_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/comments"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void addComment_shouldReturn200() throws Exception {
        when(addCommentUseCase.execute(any())).thenReturn(fakeComment);

        mockMvc.perform(post("/api/comments")
                        .header("X-User-Id", "user-72")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_INTERNAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cmt-001"))
                .andExpect(jsonPath("$.userId").value("user-72"))
                .andExpect(jsonPath("$.mediaId").value("media-001"))
                .andExpect(jsonPath("$.content").value("Super film !"));
    }

    @Test
    void addComment_withOptionalHeaders_shouldReturn200() throws Exception {
        when(addCommentUseCase.execute(any())).thenReturn(fakeComment);

        mockMvc.perform(post("/api/comments")
                        .header("X-User-Id",         "user-72")
                        .header("X-User-Numeric-Id", "72")
                        .header("X-Username",        "john")
                        .header("X-Owner-Id",        "owner-1")
                        .header("X-Media-Title",     "Inception")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_INTERNAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cmt-001"));
    }

    @Test
    void addComment_missingUserId_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_INTERNAL))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addComment_blankContent_shouldReturn500() throws Exception {
        when(addCommentUseCase.execute(any()))
                .thenThrow(new IllegalArgumentException("Content cannot be blank"));

        mockMvc.perform(post("/api/comments")
                        .header("X-User-Id", "user-72")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_BLANK))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void addComment_missingBody_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/comments")
                        .header("X-User-Id", "user-72")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }


    @Test
    void deleteComment_shouldReturn204() throws Exception {
        doNothing().when(deleteCommentUseCase).execute("cmt-001", "user-72");

        mockMvc.perform(delete("/api/comments/cmt-001")
                        .header("X-User-Id", "user-72"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteComment_missingUserId_shouldReturn400() throws Exception {
        mockMvc.perform(delete("/api/comments/cmt-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteComment_notOwner_shouldReturn500() throws Exception {
        doThrow(new RuntimeException("Not authorized"))
                .when(deleteCommentUseCase).execute("cmt-001", "user-other");

        mockMvc.perform(delete("/api/comments/cmt-001")
                        .header("X-User-Id", "user-other"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void deleteComment_notFound_shouldReturn500() throws Exception {
        doThrow(new RuntimeException("Comment not found"))
                .when(deleteCommentUseCase).execute("cmt-999", "user-72");

        mockMvc.perform(delete("/api/comments/cmt-999")
                        .header("X-User-Id", "user-72"))
                .andExpect(status().isInternalServerError());
    }


    @Test
    void getByExternalMedia_shouldReturn200WithList() throws Exception {
        when(getCommentsByMediaUseCase.execute("tmdb-movie-550"))
                .thenReturn(List.of(fakeExternalComment));

        mockMvc.perform(get("/api/comments/external").param("externalKey", "tmdb-movie-550"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("cmt-002"))
                .andExpect(jsonPath("$[0].mediaId").value("tmdb-movie-550"));
    }

    @Test
    void getByExternalMedia_emptyList_shouldReturn200() throws Exception {
        when(getCommentsByMediaUseCase.execute("tmdb-movie-550")).thenReturn(List.of());

        mockMvc.perform(get("/api/comments/external").param("externalKey", "tmdb-movie-550"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getByExternalMedia_missingParam_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/comments/external"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void addExternalComment_shouldReturn200() throws Exception {
        when(addCommentUseCase.execute(any())).thenReturn(fakeExternalComment);

        mockMvc.perform(post("/api/comments/external")
                        .header("X-User-Id", "user-72")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_EXTERNAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cmt-002"))
                .andExpect(jsonPath("$.userId").value("user-72"))
                .andExpect(jsonPath("$.mediaId").value("tmdb-movie-550"));
    }

    @Test
    void addExternalComment_missingUserId_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/comments/external")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_EXTERNAL))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addExternalComment_blankContent_shouldReturn500() throws Exception {
        when(addCommentUseCase.execute(any()))
                .thenThrow(new IllegalArgumentException("Content cannot be blank"));

        mockMvc.perform(post("/api/comments/external")
                        .header("X-User-Id", "user-72")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_BLANK))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void addExternalComment_missingBody_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/comments/external")
                        .header("X-User-Id", "user-72")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }


    @Test
    void deleteExternalComment_shouldReturn204() throws Exception {
        doNothing().when(deleteCommentUseCase).execute("cmt-002", "user-72");

        mockMvc.perform(delete("/api/comments/external/cmt-002")
                        .header("X-User-Id", "user-72"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteExternalComment_missingUserId_shouldReturn400() throws Exception {
        mockMvc.perform(delete("/api/comments/external/cmt-002"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteExternalComment_notOwner_shouldReturn500() throws Exception {
        doThrow(new RuntimeException("Not authorized"))
                .when(deleteCommentUseCase).execute("cmt-002", "user-other");

        mockMvc.perform(delete("/api/comments/external/cmt-002")
                        .header("X-User-Id", "user-other"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void deleteExternalComment_notFound_shouldReturn500() throws Exception {
        doThrow(new RuntimeException("Comment not found"))
                .when(deleteCommentUseCase).execute("cmt-999", "user-72");

        mockMvc.perform(delete("/api/comments/external/cmt-999")
                        .header("X-User-Id", "user-72"))
                .andExpect(status().isInternalServerError());
    }
}