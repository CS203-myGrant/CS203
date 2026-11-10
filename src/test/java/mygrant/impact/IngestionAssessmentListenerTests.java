package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

class IngestionAssessmentListenerTests {
    @Test
    void rejectedSubmissionDoesNotFailCommittedIngestion() {
        var executor = mock(AsyncTaskExecutor.class);
        var service = mock(IngestionAssessmentService.class);
        doThrow(new TaskRejectedException("Executor stopped")).when(executor).execute(any(Runnable.class));
        var listener = new IngestionAssessmentListener(executor, service);
        assertThatCode(() -> listener.onAssessmentRequested(new PolicyAssessmentRequested(1L)))
                .doesNotThrowAnyException();
        verifyNoInteractions(service);
    }

    @Test
    void workerFailureDoesNotEscapeBackgroundTask() {
        var executor = mock(AsyncTaskExecutor.class);
        var service = mock(IngestionAssessmentService.class);
        doThrow(new IllegalStateException("Storage unavailable")).when(service).assessMatchingProfiles(1L);
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        var listener = new IngestionAssessmentListener(executor, service);
        assertThatCode(() -> listener.onAssessmentRequested(new PolicyAssessmentRequested(1L)))
                .doesNotThrowAnyException();
        verify(service).assessMatchingProfiles(1L);
    }
}
