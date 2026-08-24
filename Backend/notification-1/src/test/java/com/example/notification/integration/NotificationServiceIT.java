package com.example.notification.integration;

import com.example.notification.domain.entity.Notification;
import com.example.notification.domain.entity.NotificationPreference;
import com.example.notification.domain.enums.NotificationStatus;
import com.example.notification.domain.enums.NotificationType;
import com.example.notification.domain.enums.ReferenceType;
import com.example.notification.dto.response.BadgeCountResponse;
import com.example.notification.dto.response.NotificationResponse;
import com.example.notification.dto.response.PageResponse;
import com.example.notification.repository.NotificationPreferenceRepository;
import com.example.notification.repository.NotificationRepository;
import com.example.notification.service.NotificationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("[IT] NotificationService – End-to-End")
class NotificationServiceIT {

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        preferenceRepository.deleteAll();
    }


    @Nested
    @DisplayName("send() – persistance")
    class SendPersistence {

        @Test
        @DisplayName("persiste la notification en base avec les bons champs")
        void persistsNotificationWithCorrectFields() {
            notificationService.send(1L, NotificationType.MEDIA_LIKED,
                    "Tu as eu un like", 42L, ReferenceType.MEDIA);

            List<Notification> all = notificationRepository.findAll();
            assertThat(all).hasSize(1);

            Notification saved = all.get(0);
            assertThat(saved.getUserId()).isEqualTo(1L);
            assertThat(saved.getType()).isEqualTo(NotificationType.MEDIA_LIKED);
            assertThat(saved.getMessage()).isEqualTo("Tu as eu un like");
            assertThat(saved.getReferenceId()).isEqualTo(42L);
            assertThat(saved.getReferenceType()).isEqualTo(ReferenceType.MEDIA);
            assertThat(saved.getStatus()).isEqualTo(NotificationStatus.UNREAD);
            assertThat(saved.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("utilise la préférence existante (ne la persiste pas en double)")
        void usesExistingPreferenceWithoutDuplication() {
            preferenceRepository.save(NotificationPreference.builder()
                    .userId(1L)
                    .type(NotificationType.COMMENT_ADDED)
                    .inAppEnabled(true)
                    .emailEnabled(true)
                    .build());

            notificationService.send(1L, NotificationType.COMMENT_ADDED,
                    "Nouveau commentaire", 10L, ReferenceType.COMMENT);

            assertThat(preferenceRepository.findByUserId(1L)).hasSize(1);
        }

        @Test
        @DisplayName("ne persiste pas de préférence par défaut si elle n'existait pas")
        void doesNotPersistDefaultPreference() {
            notificationService.send(1L, NotificationType.RECO_READY,
                    "Reco prête", null, ReferenceType.SYSTEM);

            assertThat(preferenceRepository.findAll()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getMyNotifications()")
    class GetMyNotifications {

        @Test
        @DisplayName("retourne les notifications d'un user, triées du plus récent au plus vieux")
        void returnsSortedNotificationsForUser() {
            notificationService.send(1L, NotificationType.MEDIA_LIKED, "msg1", 1L, ReferenceType.MEDIA);
            notificationService.send(1L, NotificationType.BROADCAST, "msg2", null, ReferenceType.SYSTEM);
            notificationService.send(2L, NotificationType.MEDIA_LIKED, "msg3", 2L, ReferenceType.MEDIA);

            PageResponse<NotificationResponse> page = notificationService.getMyNotifications(
                    1L, PageRequest.of(0, 10, Sort.by("createdAt").descending()));

            assertThat(page.getContent()).hasSize(2);
            assertThat(page.getTotalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("champ 'read' est false pour les notifications UNREAD")
        void readFieldIsFalseWhenUnread() {
            notificationService.send(1L, NotificationType.MEDIA_LIKED, "msg", 1L, ReferenceType.MEDIA);

            PageResponse<NotificationResponse> page = notificationService.getMyNotifications(
                    1L, PageRequest.of(0, 10));

            assertThat(page.getContent().get(0).isRead()).isFalse();
            assertThat(page.getContent().get(0).getStatus()).isEqualTo(NotificationStatus.UNREAD);
        }
    }


    @Nested
    @DisplayName("getBadgeCount()")
    class GetBadgeCount {

        @Test
        @DisplayName("retourne le bon nombre de non lues")
        void returnsCorrectUnreadCount() {
            notificationService.send(1L, NotificationType.MEDIA_LIKED, "m1", 1L, ReferenceType.MEDIA);
            notificationService.send(1L, NotificationType.BROADCAST, "m2", null, ReferenceType.SYSTEM);
            notificationService.send(2L, NotificationType.MEDIA_LIKED, "m3", 2L, ReferenceType.MEDIA);

            BadgeCountResponse badge = notificationService.getBadgeCount(1L);

            assertThat(badge.getUnreadCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("decremente après markAsRead")
        void decrementAfterMarkAsRead() {
            notificationService.send(1L, NotificationType.MEDIA_LIKED, "m1", 1L, ReferenceType.MEDIA);
            notificationService.send(1L, NotificationType.BROADCAST, "m2", null, ReferenceType.SYSTEM);

            Notification notif = notificationRepository.findAll().get(0);
            notificationService.markAsRead(notif.getId(), 1L);

            BadgeCountResponse badge = notificationService.getBadgeCount(1L);
            assertThat(badge.getUnreadCount()).isEqualTo(1);
        }
    }


    @Nested
    @DisplayName("markAsRead()")
    class MarkAsRead {

        @Test
        @DisplayName("marque une notification comme lue en base")
        void marksNotificationAsReadInDb() {
            notificationService.send(1L, NotificationType.MEDIA_LIKED, "msg", 1L, ReferenceType.MEDIA);
            Notification notif = notificationRepository.findAll().get(0);

            notificationService.markAsRead(notif.getId(), 1L);

            Notification updated = notificationRepository.findById(notif.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(NotificationStatus.READ);
            assertThat(updated.getReadAt()).isNotNull();
        }

        @Test
        @DisplayName("n'affecte pas la notification d'un autre user (sécurité)")
        void doesNotMarkOtherUserNotification() {
            notificationService.send(2L, NotificationType.MEDIA_LIKED, "msg", 1L, ReferenceType.MEDIA);
            Notification notif = notificationRepository.findAll().get(0);

            notificationService.markAsRead(notif.getId(), 1L);

            assertThat(notificationRepository.findById(notif.getId()).orElseThrow().getStatus())
                    .isEqualTo(NotificationStatus.UNREAD);
        }
    }


    @Nested
    @DisplayName("markAllAsRead()")
    class MarkAllAsRead {

        @Test
        @DisplayName("toutes les notifications de l'user passent en READ")
        void marksAllNotificationsRead() {
            notificationService.send(1L, NotificationType.MEDIA_LIKED, "m1", 1L, ReferenceType.MEDIA);
            notificationService.send(1L, NotificationType.BROADCAST, "m2", null, ReferenceType.SYSTEM);
            notificationService.send(1L, NotificationType.COMMENT_ADDED, "m3", 2L, ReferenceType.COMMENT);

            notificationService.markAllAsRead(1L);

            assertThat(notificationService.getBadgeCount(1L).getUnreadCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("n'affecte pas les notifications des autres users")
        void doesNotAffectOtherUsers() {
            notificationService.send(1L, NotificationType.MEDIA_LIKED, "m1", 1L, ReferenceType.MEDIA);
            notificationService.send(2L, NotificationType.BROADCAST, "m2", null, ReferenceType.SYSTEM);

            notificationService.markAllAsRead(1L);

            assertThat(notificationService.getBadgeCount(2L).getUnreadCount()).isEqualTo(1);
        }
    }
}