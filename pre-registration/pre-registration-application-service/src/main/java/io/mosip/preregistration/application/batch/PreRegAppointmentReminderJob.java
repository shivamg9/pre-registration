//package io.mosip.preregistration.application.batch;
//
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.ZoneId;
//import java.util.List;
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//import io.mosip.kernel.core.logger.spi.Logger;
//import io.mosip.preregistration.application.repository.ApplicationRepostiory;
//import io.mosip.preregistration.application.service.NotificationService;
//import io.mosip.preregistration.core.common.dto.NotificationDTO;
//import io.mosip.preregistration.core.common.entity.ApplicationEntity;
//import io.mosip.preregistration.core.config.LoggerConfiguration;
//import org.springframework.transaction.annotation.Transactional;
//
//@Component
//public class PreRegAppointmentReminderJob {
//
//    private Logger log = LoggerConfiguration.logConfig(PreRegAppointmentReminderJob.class);
//
//    @Autowired
//    private ApplicationRepostiory applicationRepository;
//
//    @Autowired
//    private NotificationService notificationService;
//
//    @Value("${preregistration.notification.nameFormat}")
//    private String nameFormat;
//
//    //@Scheduled(cron = "${preregistration.job.schedule.cron.remindernotification}")
//    @Scheduled(cron = "0 0 7 * * ?") // Run at 7:00 AM every day.  Use this in production.
//    @Transactional
//    public void sendAppointmentReminders() {
//        log.info("sessionId", "idType", "id", "Starting appointment reminder batch job.");
//
//        LocalDate tomorrow = LocalDate.now(ZoneId.of("UTC")).plusDays(1);
//        List<ApplicationEntity> applications = applicationRepository.findByAppointmentDate(tomorrow);
//
//        if (applications == null || applications.isEmpty()) {
//            log.info("sessionId", "idType", "id", "No appointments found for tomorrow.");
//            return;
//        }
//
//        log.info("sessionId", "idType", "id", "Found {} appointments for tomorrow.", applications.size());
//
//        for (ApplicationEntity application : applications) {
//            try {
//                log.info("In for loop of sendAppointmentReminders for Prid {}", application.getApplicationId());
//                // Construct a NotificationDTO.
//                //How you get email/phone depends on your existing logic (DemographicService?).
//
//                NotificationDTO notificationDto = new NotificationDTO();
//                notificationDto.setPreRegistrationId(application.getApplicationId());
//                notificationDto.setAppointmentDate(application.getAppointmentDate().toString());
//                notificationDto.setAppointmentTime(application.getSlotFromTime().format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a")));
//                notificationDto.setAdditionalRecipient(false);
//                notificationDto.setLangCode(application.getLangCode());// Fetch language code
//                notificationDto.setIsBatch(false);
//
//                String json = "{\"id\":\"mosip.pre-registration.notification.notify\", \"version\":\"1.0\",\"requesttime\":\""+LocalDateTime.now()+"\",\"request\":{}}";
//
//                notificationService.sendNotifications(json, application.getLangCode(), null,
//                        null);
//
//                log.info("Notification sent for pre-registration ID: {}",
//                        application.getApplicationId());
//
//            } catch (Exception e) {
//                log.error("Failed to send notification for pre-registration ID: {}", application.getApplicationId(), e);
//                // Consider adding more robust error handling (e.g., retry logic, dead-letter queue).
//            }
//        }
//        log.info("sessionId", "idType", "id", "Appointment reminder batch job completed.");
//    }
//}