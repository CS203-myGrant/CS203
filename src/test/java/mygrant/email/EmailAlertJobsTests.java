package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;

class EmailAlertJobsTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(EmailSender.class, () -> mock(EmailSender.class))
            .withBean(ImmediateAlertQueue.class, () -> mock(ImmediateAlertQueue.class))
            .withBean(EmailDispatcher.class, () -> mock(EmailDispatcher.class))
            .withUserConfiguration(EmailAlertJobs.class);

    @Test
    void noJobsRunWhenTurnedOff() {
        runner.withPropertyValues("mygrant.email.jobs-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EmailAlertJobs.class));
    }

    @Test
    void sendJobRunsAtTheConfiguredInterval() {
        runner.withPropertyValues("mygrant.email.dispatch-interval=45s").run(context -> {
            assertThat(context).hasSingleBean(EmailAlertJobs.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                    .extracting(ScheduledTask::getTask)
                    .filteredOn(FixedDelayTask.class::isInstance)
                    .singleElement()
                    .satisfies(task -> {
                        assertThat(((FixedDelayTask) task).getIntervalDuration()).isEqualTo(Duration.ofSeconds(45));
                        assertThat(((FixedDelayTask) task).getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(45));
                    });
        });
    }

    @Test
    void withoutResendSettingsTheJobDoesNothing() {
        EmailSender sender = mock(EmailSender.class);
        ImmediateAlertQueue queue = mock(ImmediateAlertQueue.class);
        EmailDispatcher dispatcher = mock(EmailDispatcher.class);
        EmailAlertJobs jobs = new EmailAlertJobs(sender, queue, dispatcher);

        jobs.sendDueEmails();
        jobs.sendDueEmails();

        verifyNoInteractions(queue, dispatcher);
    }

    @Test
    void queuesNewAlertsThenSendsAndSurvivesAFailure() {
        EmailSender sender = mock(EmailSender.class);
        ImmediateAlertQueue queue = mock(ImmediateAlertQueue.class);
        EmailDispatcher dispatcher = mock(EmailDispatcher.class);
        when(sender.isConfigured()).thenReturn(true);
        when(dispatcher.dispatchDue(any())).thenThrow(new IllegalStateException("database down"));
        EmailAlertJobs jobs = new EmailAlertJobs(sender, queue, dispatcher);

        jobs.sendDueEmails();

        InOrder order = inOrder(queue, dispatcher);
        order.verify(queue).queueDue(any());
        order.verify(dispatcher).dispatchDue(any());
        verify(queue).queueDue(any());
    }
}
