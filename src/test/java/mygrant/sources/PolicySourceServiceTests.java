package mygrant.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.sources.dto.PolicySourceRequest;
import mygrant.user.User;
import mygrant.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class PolicySourceServiceTests {

    private static final String EMAIL = "admin@example.com";

    @Mock
    private PolicySourceRepository sourceRepository;

    @Mock
    private PolicySourceChangeLogRepository changeLogRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @Captor
    private ArgumentCaptor<PolicySourceChangeLog> changeLogCaptor;

    private User admin;
    private PolicySourceService service;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId(7L);
        admin.setEmail(EMAIL);
        service = new PolicySourceService(sourceRepository, changeLogRepository, userRepository);
    }

    @Test
    void createsSourceAndRecordsAdministrator() {
        stubActor();
        stubSave();

        var response = service.create(request(true), principal);

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.name()).isEqualTo("Federal Register");
        assertThat(response.type()).isEqualTo(PolicySourceType.FEDERAL_REGISTER);
        assertThat(response.enabled()).isTrue();
        verify(changeLogRepository).save(changeLogCaptor.capture());
        assertThat(changeLogCaptor.getValue().getActor()).isSameAs(admin);
        assertThat(changeLogCaptor.getValue().getActorEmail()).isEqualTo(EMAIL);
        assertThat(changeLogCaptor.getValue().getAction()).isEqualTo("CREATE");
    }

    @Test
    void deleteDisablesSourceAndRecordsDisableInsteadOfDeleting() {
        stubActor();
        PolicySource source = new PolicySource("Federal Register",
                PolicySourceType.FEDERAL_REGISTER, "https://www.federalregister.gov", 3600);
        ReflectionTestUtils.setField(source, "id", 11L);
        when(sourceRepository.findById(11L)).thenReturn(Optional.of(source));
        when(sourceRepository.save(source)).thenReturn(source);

        var response = service.setEnabled(11L, false, principal);

        assertThat(response.enabled()).isFalse();
        verify(sourceRepository).save(source);
        verify(changeLogRepository).save(changeLogCaptor.capture());
        assertThat(changeLogCaptor.getValue().getAction()).isEqualTo("DISABLE");
    }

    @Test
    void rejectsNonHttpSourceUrls() {
        assertThatThrownBy(() -> service.create(
                new PolicySourceRequest("Source", PolicySourceType.RSS,
                        "file:///tmp/feed.xml", 60, true), principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("URL must be a valid http or https URL");

        verifyNoInteractions(userRepository, sourceRepository, changeLogRepository);
    }

    @Test
    void missingSourceIsReported() {
        when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(PolicySourceNotFoundException.class)
                .hasMessage("Policy source not found: 99");
    }

    private PolicySourceRequest request(boolean enabled) {
        return new PolicySourceRequest(" Federal Register ", PolicySourceType.FEDERAL_REGISTER,
                "https://www.federalregister.gov", 3600, enabled);
    }

    private void stubActor() {
        when(principal.getName()).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(admin));
    }

    private void stubSave() {
        when(sourceRepository.save(any(PolicySource.class))).thenAnswer(invocation -> {
            PolicySource source = invocation.getArgument(0);
            ReflectionTestUtils.setField(source, "id", 11L);
            ReflectionTestUtils.invokeMethod(source, "onCreate");
            return source;
        });
    }
}
