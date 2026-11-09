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
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;

class EmailAlertJobsTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(EmailSender.class, () -> mock(EmailSender.class))
            .withBean(ImmediateAlertQueue.class, () -> mock(ImmediateAlertQueue.class))
            .withBean(EmailDispatcher.class, () -> mock(EmailDispatcher.class))
            .withBean(DailyDigestService.class, () -> mock(DailyDigestService.class))
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
    void digestJobRunsOnTheConfiguredCronInTheDigestZone() {
        runner.withPropertyValues("mygrant.email.digest-cron=0 30 7 * * MON-FRI",
                "mygrant.email.digest-zone=Asia/Singapore").run(context -> {
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                    .extracting(ScheduledTask::getTask)
                    .filteredOn(CronTask.class::isInstance)
                    .singleElement()
                    .satisfies(task -> {
                        CronTask cron = (CronTask) task;
                        assertThat(cron.getExpression()).isEqualTo("0 30 7 * * MON-FRI");
                        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(cron.getTrigger(), "zoneId"))
                                .isEqualTo(java.time.ZoneId.of("Asia/Singapore"));
                    });
        });
    }

    @Test
    void digestJobBuildsTodaysDigestsThenSends() {
        EmailSender sender = mock(EmailSender.class);
        EmailDispatcher dispatcher = mock(EmailDispatcher.class);
        DailyDigestService digests = mock(DailyDigestService.class);
        when(sender.isConfigured()).thenReturn(true);
        when(digests.today(any())).thenReturn(java.time.LocalDate.parse("2026-10-24"));

        new EmailAlertJobs(sender, mock(ImmediateAlertQueue.class), dispatcher, digests).sendDailyDigests();

        InOrder order = inOrder(digests, dispatcher);
        order.verify(digests).buildDigests(org.mockito.ArgumentMatchers.eq(java.time.LocalDate.parse("2026-10-24")), any());
        order.verify(dispatcher).dispatchDue(any());
    }

    @Test
    void withoutResendSettingsTheJobDoesNothing() {
        EmailSender sender = mock(EmailSender.class);
        ImmediateAlertQueue queue = mock(ImmediateAlertQueue.class);
        EmailDispatcher dispatcher = mock(EmailDispatcher.class);
        DailyDigestService digests = mock(DailyDigestService.class);
        EmailAlertJobs jobs = new EmailAlertJobs(sender, queue, dispatcher, digests);

        jobs.sendDueEmails();
        jobs.sendDueEmails();
        jobs.sendDailyDigests();

        verifyNoInteractions(queue, dispatcher, digests);
    }

    @Test
    void queuesNewAlertsThenSendsAndSurvivesAFailure() {
        EmailSender sender = mock(EmailSender.class);
        ImmediateAlertQueue queue = mock(ImmediateAlertQueue.class);
        EmailDispatcher dispatcher = mock(EmailDispatcher.class);
        when(sender.isConfigured()).thenReturn(true);
        when(dispatcher.dispatchDue(any())).thenThrow(new IllegalStateException("database down"));
        EmailAlertJobs jobs = new EmailAlertJobs(sender, queue, dispatcher, mock(DailyDigestService.class));

        jobs.sendDueEmails();

        InOrder order = inOrder(queue, dispatcher);
        order.verify(queue).queueDue(any());
        order.verify(dispatcher).dispatchDue(any());
        verify(queue).queueDue(any());
    }
}
