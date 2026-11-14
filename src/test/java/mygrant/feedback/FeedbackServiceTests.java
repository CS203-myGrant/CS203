package mygrant.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;

import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;
import mygrant.manager.ManagerMembershipRepository;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTests {
    @Mock FeedbackConversationRepository conversationRepository;
    @Mock FeedbackMessageRepository messageRepository;
    @Mock UserRepository userRepository;
    @Mock ManagerMembershipRepository membershipRepository;

    private FeedbackService service;
    private User applicant;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(conversationRepository, messageRepository, userRepository, membershipRepository);
        applicant = user(1L, "Applicant", "applicant@example.com", UserRole.APPLICANT);
    }

    @Test
    void applicantMessageIsStoredInOwnedOpenConversation() {
        FeedbackConversation conversation = new FeedbackConversation(applicant.getId());
        FeedbackMessage saved = new FeedbackMessage(10L, applicant.getId(), "APPLICANT", "Please help");
        when(userRepository.findByEmail(applicant.getEmail())).thenReturn(Optional.of(applicant));
        when(conversationRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(1L, "OPEN"))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(FeedbackMessage.class))).thenReturn(saved);

        var response = service.sendAsApplicant(applicant.getEmail(), "  Please help  ");

        assertThat(response.message()).isEqualTo("Please help");
        assertThat(response.senderType()).isEqualTo("APPLICANT");
        verify(conversationRepository).save(conversation);
    }

    @Test
    void applicantCannotOpenStaffInbox() {
        when(userRepository.findByEmail(applicant.getEmail())).thenReturn(Optional.of(applicant));

        assertThatThrownBy(() -> service.staffConversations(applicant.getEmail(), PageRequest.of(0, 20)))
                .isInstanceOf(FeedbackAccessDeniedException.class);
    }

    @Test
    void adminCanReplyToConversation() {
        User admin = user(2L, "Support", "support@example.com", UserRole.ADMIN);
        FeedbackConversation conversation = new FeedbackConversation(applicant.getId());
        FeedbackMessage saved = new FeedbackMessage(10L, admin.getId(), "STAFF", "We are checking.");
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(FeedbackMessage.class))).thenReturn(saved);

        var response = service.sendAsStaff(admin.getEmail(), 10L, "We are checking.");

        assertThat(response.senderType()).isEqualTo("STAFF");
        assertThat(response.senderName()).isEqualTo("Support");
    }

    @Test
    void managerInboxOnlyQueriesManagedApplicants() {
        User manager = user(2L, "Manager", "manager@example.com", UserRole.MANAGER);
        when(userRepository.findByEmail(manager.getEmail())).thenReturn(Optional.of(manager));
        when(membershipRepository.findAllByManagerIdOrderByCreatedAtAscUserIdAsc(2L))
                .thenReturn(List.of(new mygrant.manager.ManagerMembership(2L, applicant.getId())));
        when(conversationRepository.findAllByUserIdInOrderByUpdatedAtDesc(List.of(1L), PageRequest.of(0, 20)))
                .thenReturn(Page.empty());

        service.staffConversations(manager.getEmail(), PageRequest.of(0, 20));

        verify(conversationRepository).findAllByUserIdInOrderByUpdatedAtDesc(List.of(1L), PageRequest.of(0, 20));
    }

    @Test
    void managerCannotReadConversationOutsideManagedApplicants() {
        User manager = user(2L, "Manager", "manager@example.com", UserRole.MANAGER);
        when(userRepository.findByEmail(manager.getEmail())).thenReturn(Optional.of(manager));
        when(membershipRepository.findAllByManagerIdOrderByCreatedAtAscUserIdAsc(2L)).thenReturn(List.of());
        when(conversationRepository.findByIdAndUserIdIn(10L, List.of())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.staffMessages(manager.getEmail(), 10L, PageRequest.of(0, 20)))
                .isInstanceOf(FeedbackNotFoundException.class);
    }

    private User user(Long id, String name, String email, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setFullName(name);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }
}
