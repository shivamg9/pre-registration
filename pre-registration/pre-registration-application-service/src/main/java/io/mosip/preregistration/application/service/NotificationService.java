package io.mosip.preregistration.application.service;

import java.io.IOException;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.transaction.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;

import io.mosip.preregistration.application.dto.RegistrationCenterResponseDto;
import io.mosip.preregistration.application.repository.ApplicationRepostiory;
import io.mosip.preregistration.core.common.dto.*;
import io.mosip.preregistration.core.common.entity.ApplicationEntity;
import io.mosip.preregistration.core.exception.InvalidRequestParameterException;
import org.json.JSONException;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.core.env.Environment;
import org.springframework.web.util.UriComponentsBuilder;

import io.mosip.kernel.core.authmanager.authadapter.model.AuthUserDetails;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.preregistration.application.code.NotificationRequestCodes;
import io.mosip.preregistration.application.dto.NotificationResponseDTO;
import io.mosip.preregistration.application.errorcodes.NotificationErrorCodes;
import io.mosip.preregistration.application.errorcodes.NotificationErrorMessages;
import io.mosip.preregistration.application.exception.BookingDetailsNotFoundException;
import io.mosip.preregistration.application.exception.DemographicDetailsNotFoundException;
import io.mosip.preregistration.application.exception.MandatoryFieldException;
import io.mosip.preregistration.application.exception.RestCallException;
import io.mosip.preregistration.application.exception.util.NotificationExceptionCatcher;
import io.mosip.preregistration.application.service.util.NotificationServiceUtil;
import io.mosip.preregistration.core.code.AuditLogVariables;
import io.mosip.preregistration.core.code.EventId;
import io.mosip.preregistration.core.code.EventName;
import io.mosip.preregistration.core.code.EventType;
import io.mosip.preregistration.core.config.LoggerConfiguration;
import io.mosip.preregistration.core.util.AuditLogUtil;
import io.mosip.preregistration.core.util.NotificationUtil;
import io.mosip.preregistration.core.util.ValidationUtil;
import io.mosip.preregistration.application.dto.OtpRequestDTO;
import io.mosip.preregistration.application.service.util.NotificationServiceUtil.NotificationType;

/**
 * The service class contans all the method for notification.
 *
 * @author Sanober Noor
 * @author Tapaswini Behera
 * @since 1.0.0
 *
 */
@Service
@EnableScheduling
public class NotificationService {

	/**
	 * The reference to {@link NotificationUtil}.
	 */
	@Autowired
	private NotificationUtil notificationUtil;


	/**
	 * The reference to {@link NotificationServiceUtil}.
	 */
	@Autowired
	private NotificationServiceUtil serviceUtil;

	@Autowired
	private DemographicServiceIntf demographicServiceIntf;

	/**
	 * Reference for ${appointmentResourse.url} from property file
	 */
	@Value("${appointmentResourse.url}")
	private String appointmentResourseUrl;

	@Value("${reminder.email.content.template}")
	private String reminderEmailContentTemplate;

	@Value("${reminder.email.subject.template}")
	private String reminderEmailSubjectTemplate;

	@Value("${reminder.sms.template}")
	private String reminderSmsTemplate;

	private static final Logger log = LoggerConfiguration.logConfig(NotificationService.class);

	Map<String, String> requiredRequestMap = new HashMap<>();

	@Value("${mosip.pre-registration.notification.id}")
	private String notificationServiceId;

	@Value("${version}")
	private String version;

	@Value("${mosip.utc-datetime-pattern}")
	private String mosipDateTimeFormat;

	/**
	 *
	 */
	@Value("${demographic.resource.url}")
	private String demographicResourceUrl;
	/**
	 *
	 */
	@Value("${preregistration.response}")
	private String demographicResponse;

	@Value("${preregistration.demographicDetails}")
	private String demographicDetails;

	@Value("${preregistration.identity}")
	private String identity;

	@Value("${preregistration.identity.email}")
	private String email;

	@Value("${preregistration.identity.name}")
	private String fullName;

	@Value("${preregistration.identity.phone}")
	private String phone;

	@Value("${preregistration.notification.nameFormat}")
	private String nameFormat;

	@Value("#{'${mosip.notificationtype}'.split('\\|')}")
	private List<String> notificationTypeList;

	@Value("${preregistration.default-language:eng}")
	private String defaultLanguage;


	MainResponseDTO<NotificationResponseDTO> response;


	/**
	 * Autowired reference for {@link #AuditLogUtil}
	 */
	@Autowired
	private AuditLogUtil auditLogUtil;

	@Autowired
	private ValidationUtil validationUtil;

	@Autowired
	private DemographicServiceIntf demographicService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ApplicationRepostiory applicationRepository;

	@Autowired
	private Environment environment;

	@Autowired
	private RestTemplate restTemplate;

	@Value("${regCenter.url}")
	private String regCenterUrl;

	@PostConstruct
	public void setupBookingService() {
		requiredRequestMap.put("version", version);
	}

	public AuthUserDetails authUserDetails() {
		try {
			return (AuthUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		} catch (Exception e) {
			log.error("Failed to get AuthUserDetails", e);
			// Return null or a default/empty AuthUserDetails object
			return null; // Or a suitable default/empty object
		}
	}

	@PostConstruct
	public void setup() {
		requiredRequestMap.put("version", version);
		requiredRequestMap.put("id", notificationServiceId);
		log.info("NotificationService setup complete.  requiredRequestMap: {}", requiredRequestMap);
	}

	/**
	 * Scheduled task to send appointment reminders. Runs daily at 3:35 AM UTC.
	 */
	// @Scheduled(cron = "0 30 16 * * ?")
	@Scheduled(cron = "0 17 19 * * ?", zone = "UTC")
	@Transactional
	public void sendAppointmentReminders() {
		log.info("Starting appointment reminder task.");
		
		try {
			// Get appointments for day after tomorrow to give adequate notice
			LocalDate targetDate = LocalDate.now(ZoneId.of("UTC")).plusDays(1);
			List<ApplicationEntity> applications = applicationRepository.findByAppointmentDate(targetDate);

			if (applications == null || applications.isEmpty()) {
				log.info("No appointments found for date: {}", targetDate);
				return;
			}

			log.info("Found {} appointments for date: {}", applications.size(), targetDate);

			for (ApplicationEntity application : applications) {
				try {
					log.info("Processing appointment reminder for Prid: {}", application.getApplicationId());
					processSingleReminder(application);
				} catch (Exception e) {
					log.error("Failed to send notification for pre-registration ID: {}", 
							 application.getApplicationId(), e);
					// Continue processing other applications
				}
			}
		} catch (Exception e) {
			log.error("Error in appointment reminder task", e);
		} finally {
			log.info("Appointment reminder task completed.");
		}
	}

	/**
	 * Process a single appointment reminder
	 */
	private void processSingleReminder(ApplicationEntity application) throws Exception {
		log.info("Processing appointment reminder for Prid: {}", application.getApplicationId());
		
		// Get demographic data
		MainResponseDTO<DemographicResponseDTO> demographicResponse = 
			demographicServiceIntf.getDemographicData(application.getApplicationId());
		
		if (demographicResponse == null || demographicResponse.getResponse() == null) {
			log.error("No demographic data found for PreRegID: {}", application.getApplicationId());
			return;
		}

		DemographicResponseDTO demographicData = demographicResponse.getResponse();

		// Build notification DTO
		NotificationDTO notificationDto = buildReminderNotificationDTO(application, demographicData);
		if (notificationDto == null) {
			log.error("Failed to build notification DTO for PreRegID: {}", application.getApplicationId());
			return;
		}

		// Validate notification data
		validateReminderDTO(notificationDto);

		// Prepare template values
		Map<String, Object> templateValues = new HashMap<>();
		templateValues.put("name", notificationDto.getName());
		templateValues.put("preRegistrationId", notificationDto.getPreRegistrationId());
		templateValues.put("appointmentDate", notificationDto.getAppointmentDate());
		templateValues.put("appointmentTime", notificationDto.getAppointmentTime());
		templateValues.put("registrationCenterName", notificationDto.getRegistrationCenterName());
		templateValues.put("address", notificationDto.getAddress());

		// Create dummy request DTO for notification service
		MainRequestDTO<OtpRequestDTO> dummyRequest = new MainRequestDTO<>();
		OtpRequestDTO otpRequest = new OtpRequestDTO();
		otpRequest.setUserId(notificationDto.getPreRegistrationId());
		dummyRequest.setRequest(otpRequest);

		try {
			// Send email notification if email is available
			if (notificationDto.getEmailID() != null && !notificationDto.getEmailID().isEmpty()) {
				log.info("Attempting to send email reminder to: {} for PreRegID: {}", 
						notificationDto.getEmailID(), notificationDto.getPreRegistrationId());
				serviceUtil.invokeEmailNotification(templateValues, notificationDto.getEmailID(), 
						dummyRequest, notificationDto.getLanguageCode(), NotificationType.REMINDER);
			}

			// Send SMS notification if mobile number is available
			if (notificationDto.getMobNum() != null && !notificationDto.getMobNum().isEmpty()) {
				log.info("Attempting to send SMS reminder to: {} for PreRegID: {}", 
						notificationDto.getMobNum(), notificationDto.getPreRegistrationId());
				serviceUtil.invokeSmsNotification(templateValues, notificationDto.getMobNum(), 
						dummyRequest, notificationDto.getLanguageCode(), NotificationType.REMINDER);
			}
		} catch (Exception e) {
			log.error("Error sending reminder notifications for PreRegID: {}: {}", 
					notificationDto.getPreRegistrationId(), e.getMessage());
			throw e;
		}
	}

	/**
	 * Build NotificationDTO specifically for reminders
	 */
	private NotificationDTO buildReminderNotificationDTO(ApplicationEntity application, 
													   DemographicResponseDTO demographicData) 
			throws Exception {
		NotificationDTO dto = new NotificationDTO();
		
		// Set basic information
		dto.setPreRegistrationId(application.getApplicationId());
		dto.setAppointmentDate(application.getAppointmentDate().toString());
		dto.setAppointmentTime(application.getSlotFromTime().format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a")));
		dto.setLanguageCode(demographicData.getLangCode() != null ? demographicData.getLangCode() : defaultLanguage);
		
		// Extract demographic details
		try {
			JsonNode demographicDetails = objectMapper.readTree(demographicData.getDemographicDetails().toJSONString());
			JsonNode identityNode = demographicDetails.get(identity);
			
			// Set contact information
			if (identityNode.has(email)) {
				String emailId = identityNode.get(email).asText();
				if (emailId != null && !emailId.trim().isEmpty() && 
					validationUtil.emailValidator(emailId.trim())) {
					dto.setEmailID(emailId.trim());
				}
			}
			
			if (identityNode.has(phone)) {
				String phoneNumber = identityNode.get(phone).asText();
				if (phoneNumber != null && !phoneNumber.trim().isEmpty() && 
					validationUtil.phoneValidator(phoneNumber.trim())) {
					dto.setMobNum(phoneNumber.trim());
				}
			}
			
			// Set name
			if (identityNode.has(fullName)) {
				JsonNode nameArray = identityNode.get(fullName);
				if (nameArray.isArray() && nameArray.size() > 0) {
					for (JsonNode nameNode : nameArray) {
						if (nameNode.has("language") && nameNode.has("value")) {
							String nameLangCode = nameNode.get("language").asText();
							String nameValue = nameNode.get("value").asText();
							
							if (nameValue != null && !nameValue.trim().isEmpty() && 
								nameLangCode.equals(dto.getLanguageCode())) {
								dto.setName(nameValue.trim());
								break;
							}
						}
					}
				}
			}
		} catch (Exception e) {
			log.error("Error extracting demographic details for PreRegID: {}", 
					 application.getApplicationId(), e);
			throw new DemographicDetailsNotFoundException(
				Collections.singletonList(new ExceptionJSONInfoDTO(
					"DEMOGRAPHIC_DETAILS_PARSE_ERROR", 
					"Error parsing demographic details: " + e.getMessage())), 
				response);
		}
		
		try {
			// Get registration center details
			Map<String, Object> params = new HashMap<>();
			params.put("id", application.getRegistrationCenterId());
			params.put("langcode", dto.getLanguageCode());
			
			ResponseEntity<ResponseWrapper<RegistrationCenterResponseDto>> responseEntity =
				restTemplate.exchange(
					UriComponentsBuilder.fromUriString(regCenterUrl)
						.buildAndExpand(params)
						.toString(),
					HttpMethod.GET,
					new HttpEntity<>(new HttpHeaders()),
					new ParameterizedTypeReference<ResponseWrapper<RegistrationCenterResponseDto>>() {}
				);
			
			RegistrationCenterResponseDto centerDetails = null;
			if (responseEntity.getBody() != null && 
				responseEntity.getBody().getResponse() != null) {
				centerDetails = responseEntity.getBody().getResponse();
			}
			
			if (centerDetails != null) {
				List<KeyValuePairDto<String, String>> centerName = new ArrayList<>();
				KeyValuePairDto<String, String> name = new KeyValuePairDto<>();
				name.setKey(dto.getLanguageCode());
				name.setValue(centerDetails.getName());
				centerName.add(name);
				dto.setRegistrationCenterName(centerName);
				
				List<KeyValuePairDto<String, String>> address = new ArrayList<>();
				KeyValuePairDto<String, String> addr = new KeyValuePairDto<>();
				addr.setKey(dto.getLanguageCode());
				addr.setValue(centerDetails.getAddress());
				address.add(addr);
				dto.setAddress(address);
			}
		} catch (Exception e) {
			log.error("Error setting center details for center ID: {} and language: {}", 
					 application.getRegistrationCenterId(), dto.getLanguageCode(), e);
			
			// Initialize with default values
			List<KeyValuePairDto<String, String>> centerName = new ArrayList<>();
			KeyValuePairDto<String, String> defaultName = new KeyValuePairDto<>();
			defaultName.setKey(dto.getLanguageCode());
			defaultName.setValue("Registration Center " + application.getRegistrationCenterId());
			centerName.add(defaultName);
			dto.setRegistrationCenterName(centerName);
			
			List<KeyValuePairDto<String, String>> address = new ArrayList<>();
			KeyValuePairDto<String, String> defaultAddress = new KeyValuePairDto<>();
			defaultAddress.setKey(dto.getLanguageCode());
			defaultAddress.setValue("Address not available");
			address.add(defaultAddress);
			dto.setAddress(address);
		}

		// Process templates
		try {
			// Create template variables map
			Map<String, Object> templateVariables = new HashMap<>();
			templateVariables.put("name", dto.getName());
			templateVariables.put("preRegistrationId", dto.getPreRegistrationId());
			templateVariables.put("appointmentDate", dto.getAppointmentDate());
			templateVariables.put("appointmentTime", dto.getAppointmentTime());
			
			if (dto.getRegistrationCenterName() != null && !dto.getRegistrationCenterName().isEmpty()) {
				templateVariables.put("registrationCenterName", 
					dto.getRegistrationCenterName().get(0).getValue());
			}
			
			if (dto.getAddress() != null && !dto.getAddress().isEmpty()) {
				templateVariables.put("address", dto.getAddress().get(0).getValue());
			}

			// Process templates
			String emailSubject = serviceUtil.applyTemplate(
				templateVariables,
				reminderEmailSubjectTemplate,
				dto.getLanguageCode()
			);
			
			String emailContent = serviceUtil.applyTemplate(
				templateVariables,
				reminderEmailContentTemplate,
				dto.getLanguageCode()
			);
			
			String smsContent = serviceUtil.applyTemplate(
				templateVariables,
				reminderSmsTemplate,
				dto.getLanguageCode()
			);
			
			// Store template content using templates list
			List<KeyValuePairDto<String, String>> templates = new ArrayList<>();
			
			// Add email template
			KeyValuePairDto<String, String> emailTemplate = new KeyValuePairDto<>();
			emailTemplate.setKey(dto.getLanguageCode() + "_email");
			emailTemplate.setValue(emailContent);
			templates.add(emailTemplate);
			
			// Add SMS template
			KeyValuePairDto<String, String> smsTemplate = new KeyValuePairDto<>();
			smsTemplate.setKey(dto.getLanguageCode() + "_sms");
			smsTemplate.setValue(smsContent);
			templates.add(smsTemplate);
			
			dto.setTemplates(templates);
			
			log.debug("Template processing completed for PreRegID: {} - Email Subject: {}", 
					 dto.getPreRegistrationId(), emailSubject);
			
		} catch (Exception e) {
			log.error("Error processing templates for PreRegID: {}", 
					 dto.getPreRegistrationId(), e);
			throw e;
		}

		validateReminderDTO(dto);
		return dto;
	}

	private void validateReminderDTO(NotificationDTO dto) {
		List<String> missingFields = new ArrayList<>();
		
		if (dto.getName() == null || dto.getName().trim().isEmpty()) {
			missingFields.add("name");
		}
		if (dto.getAppointmentDate() == null || dto.getAppointmentDate().trim().isEmpty()) {
			missingFields.add("appointmentDate");
		}
		if (dto.getAppointmentTime() == null || dto.getAppointmentTime().trim().isEmpty()) {
			missingFields.add("appointmentTime");
		}
		if (dto.getLanguageCode() == null || dto.getLanguageCode().trim().isEmpty()) {
			missingFields.add("languageCode");
		}
		if (dto.getPreRegistrationId() == null || dto.getPreRegistrationId().trim().isEmpty()) {
			missingFields.add("preRegistrationId");
		}
		
		// Validate that at least one contact method is available
		if ((dto.getEmailID() == null || dto.getEmailID().trim().isEmpty()) && 
			(dto.getMobNum() == null || dto.getMobNum().trim().isEmpty())) {
			missingFields.add("contact information (email or mobile)");
		}
		
		// Check registration center details
		if (dto.getRegistrationCenterName() == null || dto.getRegistrationCenterName().isEmpty()) {
			log.warn("Registration center name is missing for PreRegID: {}", dto.getPreRegistrationId());
		}
		if (dto.getAddress() == null || dto.getAddress().isEmpty()) {
			log.warn("Registration center address is missing for PreRegID: {}", dto.getPreRegistrationId());
		}
		
		if (!missingFields.isEmpty()) {
			String errorMessage = "Missing required fields for reminder notification: " + 
								String.join(", ", missingFields);
			log.error(errorMessage);
			throw new MandatoryFieldException(
				NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
				errorMessage,
				response);
		}
	}

	/**
	 * Modified sendNotification method to handle reminders better
	 */
	public MainResponseDTO<NotificationResponseDTO> sendNotification(
			String jsonString, String langCode, MultipartFile file, boolean isReminder) {
		
		response = new MainResponseDTO<>();
		NotificationResponseDTO notificationResponse = new NotificationResponseDTO();
		
		try {
			// Parse the request using JsonNode first to avoid deserialization issues
			JsonNode rootNode = objectMapper.readTree(jsonString);
			JsonNode requestNode = rootNode.get("request");
			
			if (requestNode == null) {
				throw new InvalidRequestParameterException(
					Collections.singletonList(new ExceptionJSONInfoDTO(
						"INVALID_REQUEST", "Request node is missing")), 
					response);
			}

			NotificationDTO notificationDto;
			if (isReminder) {
				// For reminders, create DTO directly from the request node
				notificationDto = new NotificationDTO();
				
				// Extract and validate required fields
				String preRegId = getNodeTextValue(requestNode, "preRegistrationId");
				String appointmentDate = getNodeTextValue(requestNode, "appointmentDate");
				String appointmentTime = getNodeTextValue(requestNode, "appointmentTime");
				String name = getNodeTextValue(requestNode, "name");
				
				if (preRegId == null || appointmentDate == null || 
					appointmentTime == null || name == null) {
					throw new MandatoryFieldException(
						NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
						"Missing required fields in reminder notification",
						response);
				}
				
				notificationDto.setPreRegistrationId(preRegId);
				notificationDto.setAppointmentDate(appointmentDate);
				notificationDto.setAppointmentTime(appointmentTime);
				notificationDto.setName(name);
				
				// Set contact information
				String emailID = getNodeTextValue(requestNode, "emailID");
				String mobNum = getNodeTextValue(requestNode, "mobNum");
				
				if (emailID != null) {
					notificationDto.setEmailID(emailID.trim());
				}
				if (mobNum != null) {
					notificationDto.setMobNum(mobNum.trim());
				}
				
				// Set language code
				String languageCode = getNodeTextValue(requestNode, "languageCode");
				notificationDto.setLanguageCode(languageCode != null ? 
											 languageCode : defaultLanguage);
				
				notificationDto.setIsBatch(true);
				notificationDto.setAdditionalRecipient(false);
				
				// Initialize center details
				List<KeyValuePairDto<String, String>> centerName = new ArrayList<>();
				String centerNameValue = getNodeTextValue(requestNode, "registrationCenterName");
				if (centerNameValue != null && !centerNameValue.trim().isEmpty()) {
					KeyValuePairDto<String, String> centerNamePair = new KeyValuePairDto<>();
					centerNamePair.setKey(notificationDto.getLanguageCode());
					centerNamePair.setValue(centerNameValue.trim());
					centerName.add(centerNamePair);
				}
				notificationDto.setRegistrationCenterName(centerName);
				
				List<KeyValuePairDto<String, String>> address = new ArrayList<>();
				String addressValue = getNodeTextValue(requestNode, "address");
				if (addressValue != null && !addressValue.trim().isEmpty()) {
					KeyValuePairDto<String, String> addressPair = new KeyValuePairDto<>();
					addressPair.setKey(notificationDto.getLanguageCode());
					addressPair.setValue(addressValue.trim());
					address.add(addressPair);
				}
				notificationDto.setAddress(address);
				
			} else {
				MainRequestDTO<NotificationDTO> notificationReqDTO = 
					serviceUtil.createNotificationDetails(jsonString, langCode, false);
				notificationDto = notificationReqDTO.getRequest();
			}

			// Validate mandatory fields
			validateNotificationDto(notificationDto);

			response.setId(rootNode.get("id").asText());
			response.setVersion(rootNode.get("version").asText());

			// Send notifications based on available contact information
			boolean notificationSent = false;
			List<String> failedNotifications = new ArrayList<>();
			
			if (notificationDto.getEmailID() != null && 
				validationUtil.emailValidator(notificationDto.getEmailID())) {
				try {
					log.info("Attempting to send email notification - PreRegID: {}, Email: {}, Name: {}", 
							notificationDto.getPreRegistrationId(),
							notificationDto.getEmailID(),
							notificationDto.getName());

					// Validate email template
//					if (notificationDto.getTemplates() == null ||
//						notificationDto.getTemplates().stream()
//							.noneMatch(t -> t.getKey().endsWith("_email"))) {
//						log.error("Missing email template for PreRegID: {}",
//								 notificationDto.getPreRegistrationId());
//						throw new MandatoryFieldException(
//							NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
//							"Missing email template",
//							response);
//					}

					notificationUtil.notify(NotificationRequestCodes.EMAIL.getCode(), 
										 notificationDto, file);
					notificationSent = true;
					log.info("Email notification sent successfully to: {}", 
							notificationDto.getEmailID());
				} catch (NullPointerException e) {
					log.error("NullPointerException while sending email - PreRegID: {}", 
							 notificationDto.getPreRegistrationId(), e);
					failedNotifications.add("email");
				}
			}
			
			if (notificationDto.getMobNum() != null && 
				validationUtil.phoneValidator(notificationDto.getMobNum())) {
				try {
					log.info("Attempting to send SMS notification - PreRegID: {}, Mobile: {}, Name: {}", 
							notificationDto.getPreRegistrationId(),
							notificationDto.getMobNum(),
							notificationDto.getName());

//					// Validate SMS template
//					if (notificationDto.getTemplates() == null ||
//						notificationDto.getTemplates().stream()
//							.noneMatch(t -> t.getKey().endsWith("_sms"))) {
//						log.error("Missing SMS template for PreRegID: {}",
//								 notificationDto.getPreRegistrationId());
//						throw new MandatoryFieldException(
//							NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
//							"Missing SMS template",
//							response);
//					}

					notificationUtil.notify(NotificationRequestCodes.SMS.getCode(), 
										 notificationDto, file);
					notificationSent = true;
					log.info("SMS notification sent successfully to: {}", 
							notificationDto.getMobNum());
				} catch (NullPointerException e) {
					log.error("NullPointerException while sending SMS - PreRegID: {}", 
							 notificationDto.getPreRegistrationId(), e);
					failedNotifications.add("SMS");
				}
			}

			if (!notificationSent) {
				if (failedNotifications.isEmpty()) {
					log.warn("No valid contact information found for ID: {}", 
							notificationDto.getPreRegistrationId());
				} else {
					log.error("All notification attempts failed for ID: {}. Failed types: {}", 
							 notificationDto.getPreRegistrationId(), 
							 String.join(", ", failedNotifications));
				}
			}

			notificationResponse.setMessage(NotificationRequestCodes.MESSAGE.getCode());
			response.setResponse(notificationResponse);
			
		} catch (Exception ex) {
			log.error("Error in sendNotification", ex);
			new NotificationExceptionCatcher().handle(ex, response);
		} finally {
			response.setResponsetime(validationUtil.getCurrentResponseTime());
		}
		
		return response;
	}

	private String getNodeTextValue(JsonNode node, String fieldName) {
		return node.has(fieldName) && !node.get(fieldName).isNull() ? 
			   node.get(fieldName).asText() : null;
	}

	private void validateNotificationDto(NotificationDTO dto) {
		List<String> missingFields = new ArrayList<>();
		List<String> invalidFields = new ArrayList<>();
		
		// Check required fields
		if (dto.getPreRegistrationId() == null || dto.getPreRegistrationId().trim().isEmpty()) {
			missingFields.add("preRegistrationId");
		}
		if (dto.getAppointmentDate() == null || dto.getAppointmentDate().trim().isEmpty()) {
			missingFields.add("appointmentDate");
		}
		if (dto.getAppointmentTime() == null || dto.getAppointmentTime().trim().isEmpty()) {
			missingFields.add("appointmentTime");
		}
		if (dto.getName() == null || dto.getName().trim().isEmpty()) {
			missingFields.add("name");
		}
		
		// Validate contact information
		boolean hasValidContact = false;
		if (dto.getEmailID() != null && !dto.getEmailID().trim().isEmpty()) {
			if (!validationUtil.emailValidator(dto.getEmailID().trim())) {
				invalidFields.add("emailID (invalid format)");
			} else {
				hasValidContact = true;
			}
		}
		
		if (dto.getMobNum() != null && !dto.getMobNum().trim().isEmpty()) {
			if (!validationUtil.phoneValidator(dto.getMobNum().trim())) {
				invalidFields.add("mobNum (invalid format)");
			} else {
				hasValidContact = true;
			}
		}
		
		if (!hasValidContact) {
			missingFields.add("contact information (either email or mobile number)");
		}
		
		// Throw exception if any validation fails
		if (!missingFields.isEmpty() || !invalidFields.isEmpty()) {
			StringBuilder errorMsg = new StringBuilder();
			if (!missingFields.isEmpty()) {
				errorMsg.append("Missing mandatory fields: ")
					   .append(String.join(", ", missingFields));
			}
			if (!invalidFields.isEmpty()) {
				if (errorMsg.length() > 0) errorMsg.append("; ");
				errorMsg.append("Invalid fields: ")
					   .append(String.join(", ", invalidFields));
			}
			
			throw new MandatoryFieldException(
				NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
				errorMsg.toString(),
				response);
		}
	}

	private List<ExceptionJSONInfoDTO> convertServiceErrors(Exception ex) {
		List<ExceptionJSONInfoDTO> errors = new ArrayList<>();
		ExceptionJSONInfoDTO error = new ExceptionJSONInfoDTO();
		error.setErrorCode("NOTIFICATION_ERROR"); //  set an appropriate error code
		error.setMessage(ex.getMessage()); //  set the exception message
		errors.add(error);
		return errors;
	}

	private Map<String, Object> buildNotificationRequest(NotificationDTO notificationDto) {
		// Validate input
		if (notificationDto == null) {
			throw new InvalidRequestParameterException(
				Collections.singletonList(new ExceptionJSONInfoDTO(
					"INVALID_REQUEST", "NotificationDTO is null")), 
				response);
		}

		Map<String, Object> innerRequest = new HashMap<>();
		
		// Required fields - validate and set
		if (notificationDto.getPreRegistrationId() == null || notificationDto.getPreRegistrationId().trim().isEmpty()) {
			throw new MandatoryFieldException(
				NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
				"PreRegistrationId is mandatory",
				response);
		}
		innerRequest.put("preRegistrationId", notificationDto.getPreRegistrationId());

		if (notificationDto.getAppointmentDate() == null || notificationDto.getAppointmentDate().trim().isEmpty()) {
			throw new MandatoryFieldException(
				NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
				"AppointmentDate is mandatory",
				response);
		}
		innerRequest.put("appointmentDate", notificationDto.getAppointmentDate());

		if (notificationDto.getAppointmentTime() == null || notificationDto.getAppointmentTime().trim().isEmpty()) {
			throw new MandatoryFieldException(
				NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
				"AppointmentTime is mandatory",
				response);
		}
		innerRequest.put("appointmentTime", notificationDto.getAppointmentTime());

		// Contact information - at least one should be present
		String mobNum = notificationDto.getMobNum();
		String emailID = notificationDto.getEmailID();
		
		if ((mobNum == null || mobNum.trim().isEmpty()) && 
			(emailID == null || emailID.trim().isEmpty())) {
			throw new MandatoryFieldException(
				NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
				"At least one contact method (mobile or email) is required",
				response);
		}
		
		innerRequest.put("mobNum", mobNum != null ? mobNum.trim() : "");
		innerRequest.put("emailID", emailID != null ? emailID.trim() : "");

		// Name validation
		if (notificationDto.getName() == null || notificationDto.getName().trim().isEmpty()) {
			throw new MandatoryFieldException(
				NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
				"Name is mandatory",
				response);
		}
		innerRequest.put("name", notificationDto.getName().trim());

		// Language code validation
		String langCode = notificationDto.getLanguageCode();
		if (langCode == null || langCode.trim().isEmpty()) {
			langCode = defaultLanguage;
			log.info("Using default language code: {} for preRegistrationId: {}", 
					defaultLanguage, notificationDto.getPreRegistrationId());
		}
		innerRequest.put("languageCode", langCode);
		innerRequest.put("langCode", langCode);

		// Batch and recipient flags
		innerRequest.put("isBatch", notificationDto.getIsBatch() != null ? 
								   notificationDto.getIsBatch() : false);
		innerRequest.put("additionalRecipient", notificationDto.isAdditionalRecipient());

		// Center details - ensure they're not null
		List<KeyValuePairDto<String, String>> centerName = notificationDto.getRegistrationCenterName();
		List<KeyValuePairDto<String, String>> address = notificationDto.getAddress();
		
		innerRequest.put("registrationCenterName", 
			centerName != null && !centerName.isEmpty() ? centerName.get(0).getValue() : "");
		innerRequest.put("address", 
			address != null && !address.isEmpty() ? address.get(0).getValue() : "");

		// Build outer request
		Map<String, Object> outerRequest = new HashMap<>();
		outerRequest.put("id", notificationServiceId);
		outerRequest.put("version", version);
		outerRequest.put("requesttime", DateTimeFormatter.ofPattern(mosipDateTimeFormat)
			.format(LocalDateTime.now(ZoneId.of("UTC"))));
		outerRequest.put("request", innerRequest);

		return outerRequest;
	}

	public MainResponseDTO<DemographicResponseDTO> notificationDtoValidation(NotificationDTO dto)
			throws IOException, ParseException {
		MainResponseDTO<DemographicResponseDTO> demoDetail = getDemographicDetails(dto);
		if (!dto.getIsBatch()) {
			//Validate booking details and modify the center details
			validateBookingDetails(dto);
		}
		return demoDetail;
	}

	/**
	 * Validates the booking details against the provided DTO and modifies the center details.
	 *
	 * @param dto The notification DTO containing appointment and pre-registration details.
	 * @throws MandatoryFieldException        If mandatory fields are missing or incorrect.
	 * @throws BookingDetailsNotFoundException If appointment details are not found for the given pre-registration ID.
	 */
	private void validateBookingDetails(NotificationDTO dto) {
		BookingRegistrationDTO bookingDTO = getAppointmentDetailsRestService(dto.getPreRegistrationId());

		// Convert 24-hour format to 12-hour format for comparison
		String time = LocalTime.parse(bookingDTO.getSlotFromTime(), DateTimeFormatter.ofPattern("HH:mm"))
				.format(DateTimeFormatter.ofPattern("hh:mm a"));

		log.info("sessionId", "idType", "id", "In notificationDtoValidation with bookingDTO " + bookingDTO);

		// Validate appointment date
		if (dto.getAppointmentDate() == null || dto.getAppointmentDate().trim().isEmpty()) {
			throw new MandatoryFieldException(NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
					NotificationErrorMessages.INCORRECT_MANDATORY_FIELDS.getMessage(), response);
		}
		if (!bookingDTO.getRegDate().equals(dto.getAppointmentDate())) {
			throw new MandatoryFieldException(NotificationErrorCodes.PRG_PAM_ACK_009.getCode(),
					NotificationErrorMessages.APPOINTMENT_DATE_NOT_CORRECT.getMessage(), response);
		}

		// Validate appointment time
		if (dto.getAppointmentTime() == null || dto.getAppointmentTime().trim().isEmpty()) {
			throw new MandatoryFieldException(NotificationErrorCodes.PRG_PAM_ACK_002.getCode(),
					NotificationErrorMessages.INCORRECT_MANDATORY_FIELDS.getMessage(), response);
		}
		if (!time.equals(dto.getAppointmentTime())) {
			throw new MandatoryFieldException(NotificationErrorCodes.PRG_PAM_ACK_010.getCode(),
					NotificationErrorMessages.APPOINTMENT_TIME_NOT_CORRECT.getMessage(), response);
		}

		// Modify center name and address, handling potential ArrayIndexOutOfBoundsException
		try {
			String langCode = dto.getLanguageCode().split(",")[0];
			dto = serviceUtil.modifyCenterNameAndAddress(dto, bookingDTO.getRegistrationCenterId(), langCode);
		} catch (ArrayIndexOutOfBoundsException e) {
			log.warn("Language code string is invalid: {}", dto.getLanguageCode(), e);
			// Use default language or handle the error appropriately
			dto = serviceUtil.modifyCenterNameAndAddress(dto, bookingDTO.getRegistrationCenterId(), defaultLanguage);
		}
	}

	/**
	 * This method is calling demographic getApplication service to validate the
	 * demographic details
	 *
	 * @param notificationDto
	 * @return DemographicResponseDTO
	 * @throws ParseException
	 */

	public MainResponseDTO<DemographicResponseDTO> getDemographicDetails(NotificationDTO notificationDto)
			throws IOException, ParseException {
		MainResponseDTO<DemographicResponseDTO> responseEntity = demographicServiceIntf
				.getDemographicData(notificationDto.getPreRegistrationId());
         /*ObjectMapper objectMapper = new ObjectMapper();
         objectMapper = JsonMapper.builder().addModule(new AfterburnerModule()).build();
         objectMapper.registerModule(new JavaTimeModule());*/

		if (responseEntity.getErrors() != null) {
			throw new DemographicDetailsNotFoundException(responseEntity.getErrors(), response);
		}
		//Check for demographic details
		if (responseEntity.getResponse() == null || responseEntity.getResponse().getDemographicDetails() == null) {
			throw new DemographicDetailsNotFoundException(
					Collections.singletonList(new ExceptionJSONInfoDTO("DEMO_GRAPHIC_DETAILS_RESPONSE_NULL", "Demo graphic response is null"))
					, response);
		}
		JsonNode responseNode = objectMapper.readTree(responseEntity.getResponse().getDemographicDetails().toJSONString());

		//Check identity and fullname
		if (!responseNode.has(identity)) {
			throw new DemographicDetailsNotFoundException(
					Collections.singletonList(new ExceptionJSONInfoDTO("DEMOGRAPHIC_IDENTITY_NOT_FOUND", "Identity not found"))
					, response);
		}

		responseNode = responseNode.get(identity);

		if (!responseNode.has(fullName)) {
			throw new DemographicDetailsNotFoundException(
					Collections.singletonList(new ExceptionJSONInfoDTO("DEMOGRAPHIC_FULLNAME_NOT_FOUND", "Full Name not found"))
					, response);
		}
		if (!notificationDto.isAdditionalRecipient()) {
			if (notificationDto.getMobNum() != null || notificationDto.getEmailID() != null) {
				log.error("sessionId", "idType", "id",
						"Not considering the requested mobilenumber/email since additional recipient is false ");
			}
		}

		// FULL NAME VALIDATION (moved here, and uses notificationDto)
		boolean isNameMatchFound = false;
		if (!notificationDto.getIsBatch() && notificationDto.getName() != null) { // Added null check for name
			if (nameFormat != null) {
				String[] nameKeys = nameFormat.split(",");
				for (String nameKey : nameKeys) {
					if (!responseNode.has(nameKey)) {
						log.error("Name Key {} is missing in response", nameKey);
						continue; // Or perhaps throw an exception.
					}
					JsonNode arrayNode = responseNode.get(nameKey);
					if (arrayNode.isArray()) {
						for (JsonNode nameNode : arrayNode) {
							// Compare the name from the DTO
							if (nameNode.has("value") && notificationDto.getName().trim().equalsIgnoreCase(nameNode.get("value").asText().trim())) {
								isNameMatchFound = true;
								break; // name found
							}
						}
					}
					if (isNameMatchFound) break;
				}
			}
		} else if (!notificationDto.getIsBatch() && notificationDto.getName() == null) {
			log.warn("Name in NotificationDTO is null for preRegistrationId: {}.  Cannot validate name.", notificationDto.getPreRegistrationId());
		} // Now handles null name case without error.

		if (!isNameMatchFound && !notificationDto.getIsBatch()) {
			throw new MandatoryFieldException(NotificationErrorCodes.PRG_PAM_ACK_008.getCode(),
					NotificationErrorMessages.FULL_NAME_VALIDATION_EXCEPTION.getMessage(), response);
		}
		return responseEntity;
	}


	/**
	 * This Method is used to retrieve booking data
	 *
	 * @param preId
	 * @return BookingRegistrationDTO
	 *
	 */
	public BookingRegistrationDTO getAppointmentDetailsRestService(String preId) {
		log.info("sessionId", "idType", "id", "In getAppointmentDetailsRestService method of notification service ");

		BookingRegistrationDTO bookingRegistrationDTO = null;
		MainResponseDTO<BookingRegistrationDTO> respEntity = notificationUtil.getAppointmentDetails(preId);
		if (respEntity.getErrors() != null) {
			throw new BookingDetailsNotFoundException(respEntity.getErrors(), response);
		}
		//Check for null
		if(respEntity.getResponse()==null){
			throw new BookingDetailsNotFoundException(
					Collections.singletonList(new ExceptionJSONInfoDTO("BOOKING_DETAILS_NOT_FOUND","Booking details not found for pre id "+preId))
					,response);
		}
		bookingRegistrationDTO = respEntity.getResponse();
		return bookingRegistrationDTO;
	}
}
