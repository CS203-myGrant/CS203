package mygrant.notifications;

public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(Long id) {
        super("Notification was not found: " + id);
    }
}
